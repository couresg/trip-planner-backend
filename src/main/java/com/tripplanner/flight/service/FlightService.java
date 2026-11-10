package com.tripplanner.flight.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.client.FlightClient;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.flight.domain.FlightCacheEntry;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.repository.FlightCacheRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 항공편 조회 + 캐시. 조회 순서: 메모리(Caffeine) → DB(flight_cache, 만료 전) → 공급자 → 메모리·DB 저장.
 * 캐시 TTL은 가장 이른 구간 출발 48시간 전부터 가장 늦은 구간 도착 3시간 뒤까지 10분, 그 외는 6시간이다
 * (48시간 구간 전이면 구간 시작까지로 줄인다).
 * 공급자 장애 시 만료된 DB 캐시라도 있으면 그 값을 쓰고, 장애 후 30초 동안은 공급자를 다시 부르지 않는다.
 * 같은 키의 동시 요청은 공급자를 한 번만 호출한다.
 *
 * <p>외부 API 호출 동안 DB 커넥션을 잡지 않도록 트랜잭션으로 감싸지 않는다 (리포지토리 호출 단위로 처리).
 */
@Service
public class FlightService {

    private static final Logger log = LoggerFactory.getLogger(FlightService.class);
    private static final TypeReference<List<FlightLeg>> LEGS_TYPE = new TypeReference<>() {
    };

    static final Duration NEAR_DEPARTURE = Duration.ofHours(48);
    static final Duration AFTER_ARRIVAL = Duration.ofHours(3);
    static final Duration SHORT_TTL = Duration.ofMinutes(10);
    static final Duration LONG_TTL = Duration.ofHours(6);
    /** 찾지 못한 편명은 DB에 저장하지 않고 메모리에만 이 시간 동안 둔다. */
    static final Duration NOT_FOUND_TTL = Duration.ofMinutes(10);
    /** 공급자 장애 후 이 시간 동안은 공급자를 호출하지 않는다 (장애 중 반복 호출 방지). */
    static final Duration PROVIDER_FAILURE_BACKOFF = Duration.ofSeconds(30);

    private static final Comparator<FlightLeg> BY_DEPARTURE =
            Comparator.comparing(leg -> leg.departure().effectiveTime());

    private final FlightClient flightClient;
    private final FlightCacheRepository cacheRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** 만료 판단은 Clock 기준(expiresAt)으로 하고, Caffeine 만료는 메모리 상한 용도로만 쓴다. */
    private final Cache<CacheKey, CachedLegs> memory = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(LONG_TTL)
            .build();
    private final ConcurrentMap<CacheKey, CompletableFuture<FlightLookup>> inFlight = new ConcurrentHashMap<>();
    private volatile Instant providerBlockedUntil = Instant.MIN;

    public FlightService(FlightClient flightClient, FlightCacheRepository cacheRepository, ObjectMapper objectMapper,
            Clock clock) {
        this.flightClient = flightClient;
        this.cacheRepository = cacheRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 편명과 출발지 현지 날짜로 모든 구간을 출발 시각 순으로 반환한다.
     *
     * @throws BusinessException FLIGHT_NOT_FOUND, FLIGHT_PROVIDER_UNAVAILABLE
     */
    public List<FlightLeg> lookup(FlightNumber flightNumber, LocalDate departureLocalDate) {
        return lookupWithMeta(flightNumber, departureLocalDate).legs();
    }

    /**
     * lookup과 같되 그 값을 공급자에서 받은 시각(fetchedAt)과 만료된 DB 캐시 대체 여부(stale)를 함께 준다.
     * 저장된 여행 항공편 재조회가 "마지막 확인 시각"과 오래된 값 덮어쓰기 방지에 쓴다.
     *
     * @throws BusinessException FLIGHT_NOT_FOUND, FLIGHT_PROVIDER_UNAVAILABLE
     */
    public FlightLookup lookupWithMeta(FlightNumber flightNumber, LocalDate departureLocalDate) {
        CacheKey key = new CacheKey(flightNumber.value(), departureLocalDate);
        CachedLegs cached = memory.getIfPresent(key);
        if (cached != null && cached.isFresh(clock.instant())) {
            return orNotFound(cached);
        }

        CompletableFuture<FlightLookup> mine = new CompletableFuture<>();
        CompletableFuture<FlightLookup> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            FlightLookup result = load(flightNumber, key);
            mine.complete(result);
            return result;
        } catch (Throwable e) {
            // Error까지 전달해야 대기 중인 요청이 join()에서 영원히 멈추지 않는다.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** 키별로 한 요청만 실행한다. */
    private FlightLookup load(FlightNumber flightNumber, CacheKey key) {
        // DATETIME(6)과 같은 정밀도로 맞춰야 저장된 fetched_at과 캐시의 fetchedAt이 같다고 비교된다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        CachedLegs cached = memory.getIfPresent(key);
        if (cached != null && cached.isFresh(now)) {
            return orNotFound(cached);
        }

        Optional<FlightCacheEntry> row = cacheRepository.findByFlightNoAndFlightDate(key.flightNo(), key.date());
        if (row.isPresent() && row.get().isFresh(now)) {
            Optional<List<FlightLeg>> legs = read(row.get());
            if (legs.isPresent()) {
                memory.put(key, new CachedLegs(legs.get(), row.get().getFetchedAt(), row.get().getExpiresAt()));
                return new FlightLookup(legs.get(), row.get().getFetchedAt(), false);
            }
        }

        if (now.isBefore(providerBlockedUntil)) {
            return staleOrUnavailable(row, null);
        }
        List<FlightLeg> legs;
        try {
            legs = flightClient.findLegs(flightNumber, key.date()).stream().sorted(BY_DEPARTURE).toList();
        } catch (FlightProviderException e) {
            providerBlockedUntil = now.plus(PROVIDER_FAILURE_BACKOFF);
            log.warn("항공편 공급자 장애: flightNo={}, date={}, reason={}, staleCache={}",
                    key.flightNo(), key.date(), e.reason(), row.isPresent());
            return staleOrUnavailable(row, e);
        }

        if (legs.isEmpty()) {
            memory.put(key, new CachedLegs(legs, now, now.plus(NOT_FOUND_TTL)));
            throw new BusinessException(ErrorCode.FLIGHT_NOT_FOUND);
        }
        Instant expiresAt = now.plus(ttl(legs, now));
        memory.put(key, new CachedLegs(legs, now, expiresAt));
        save(row, key, legs, now, expiresAt);
        return new FlightLookup(legs, now, false);
    }

    /** 가는 편: 도착 공항이 도시 공항 중 하나인 첫 구간. */
    public Optional<FlightLeg> findArrivingAt(List<FlightLeg> legs, Collection<String> airportIatas) {
        return legs.stream().filter(leg -> airportIatas.contains(leg.arrival().iata())).findFirst();
    }

    /** 오는 편: 출발 공항이 도시 공항 중 하나인 첫 구간. */
    public Optional<FlightLeg> findDepartingFrom(List<FlightLeg> legs, Collection<String> airportIatas) {
        return legs.stream().filter(leg -> airportIatas.contains(leg.departure().iata())).findFirst();
    }

    /**
     * 가장 이른 구간 출발 - 48시간 ≤ now ≤ 가장 늦은 구간 도착 + 3시간이면 짧게 (지연 재조회가 비행 중에도 새 값을 받도록).
     * 그 구간 전이면 6시간이되 구간 시작까지로 줄여, 구간이 열린 뒤에는 늦어도 10분 안에 다시 받게 한다.
     */
    static Duration ttl(List<FlightLeg> legs, Instant now) {
        // 재조회 구간이 예정 출발 기준이므로 지연된 편도 예정·변경 중 이른 시각으로 판단한다.
        Instant earliestDeparture = legs.stream()
                .flatMap(leg -> Stream.of(leg.departure().scheduled(), leg.departure().effectiveTime()))
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Instant latestArrival = legs.stream()
                .map(leg -> leg.arrival().effectiveTime())
                .max(Comparator.naturalOrder())
                .orElseThrow();
        boolean active = !now.isBefore(earliestDeparture.minus(NEAR_DEPARTURE))
                && !now.isAfter(latestArrival.plus(AFTER_ARRIVAL));
        if (active) {
            return SHORT_TTL;
        }
        Duration untilWindow = Duration.between(now, earliestDeparture.minus(NEAR_DEPARTURE));
        return untilWindow.isPositive() && untilWindow.compareTo(LONG_TTL) < 0 ? untilWindow : LONG_TTL;
    }

    /** 만료된 DB 행으로 대체한다. fetchedAt은 그 행을 받은 시각이고 stale = true. */
    private FlightLookup staleOrUnavailable(Optional<FlightCacheEntry> row, FlightProviderException cause) {
        return row.flatMap(entry -> read(entry).map(legs -> new FlightLookup(legs, entry.getFetchedAt(), true)))
                .orElseThrow(() -> cause != null
                ? cause
                : new FlightProviderException("공급자 장애 후 대기 중", null));
    }

    private void save(Optional<FlightCacheEntry> row, CacheKey key, List<FlightLeg> legs, Instant now,
            Instant expiresAt) {
        try {
            String json = objectMapper.writeValueAsString(legs);
            FlightCacheEntry entry = row.orElseGet(() -> new FlightCacheEntry(key.flightNo(), key.date(), json, now,
                    expiresAt));
            entry.refresh(json, now, expiresAt);
            cacheRepository.save(entry);
        } catch (DataAccessException | JacksonException e) {
            // 동시 저장 충돌(unique)·DB 오류·직렬화 실패. 결과는 메모리 캐시에 있으므로 응답은 계속한다.
            log.warn("flight_cache 저장 실패: flightNo={}, date={}, error={}", key.flightNo(), key.date(),
                    e.getClass().getSimpleName());
        }
    }

    /** 손상된 response_json(해석 불가·null·빈 목록·null 원소)은 캐시가 없는 것으로 본다. */
    private Optional<List<FlightLeg>> read(FlightCacheEntry entry) {
        List<FlightLeg> legs;
        try {
            legs = objectMapper.readValue(entry.getResponseJson(), LEGS_TYPE);
        } catch (JacksonException e) {
            legs = null;
        }
        if (legs == null || legs.isEmpty() || legs.contains(null)) {
            log.warn("손상된 flight_cache 행을 무시함: flightNo={}, date={}", entry.getFlightNo(), entry.getFlightDate());
            return Optional.empty();
        }
        return Optional.of(List.copyOf(legs));
    }

    private static FlightLookup await(CompletableFuture<FlightLookup> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static FlightLookup orNotFound(CachedLegs cached) {
        if (cached.legs().isEmpty()) {
            throw new BusinessException(ErrorCode.FLIGHT_NOT_FOUND);
        }
        return new FlightLookup(cached.legs(), cached.fetchedAt(), false);
    }

    private record CacheKey(String flightNo, LocalDate date) {
    }

    /** 조회 결과. fetchedAt은 공급자에서 받은 시각, stale은 공급자 장애로 만료된 DB 캐시를 대신 준 경우. */
    public record FlightLookup(List<FlightLeg> legs, Instant fetchedAt, boolean stale) {
    }

    private record CachedLegs(List<FlightLeg> legs, Instant fetchedAt, Instant expiresAt) {

        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
