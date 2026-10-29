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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 항공편 조회 + 캐시. 조회 순서: 메모리(Caffeine) → DB(flight_cache, 만료 전) → 공급자 → 메모리·DB 저장.
 * 캐시 TTL은 가장 이른 구간 출발이 지금부터 48시간 이내면 10분, 그 외(48시간 이후 또는 이미 출발)는 6시간이다.
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
    private final ConcurrentMap<CacheKey, CompletableFuture<List<FlightLeg>>> inFlight = new ConcurrentHashMap<>();
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
        CacheKey key = new CacheKey(flightNumber.value(), departureLocalDate);
        CachedLegs cached = memory.getIfPresent(key);
        if (cached != null && cached.isFresh(clock.instant())) {
            return orNotFound(cached.legs());
        }

        CompletableFuture<List<FlightLeg>> mine = new CompletableFuture<>();
        CompletableFuture<List<FlightLeg>> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return await(running);
        }
        try {
            List<FlightLeg> legs = load(flightNumber, key);
            mine.complete(legs);
            return legs;
        } catch (Throwable e) {
            // Error까지 전달해야 대기 중인 요청이 join()에서 영원히 멈추지 않는다.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /** 키별로 한 요청만 실행한다. */
    private List<FlightLeg> load(FlightNumber flightNumber, CacheKey key) {
        Instant now = clock.instant();
        CachedLegs cached = memory.getIfPresent(key);
        if (cached != null && cached.isFresh(now)) {
            return orNotFound(cached.legs());
        }

        Optional<FlightCacheEntry> row = cacheRepository.findByFlightNoAndFlightDate(key.flightNo(), key.date());
        if (row.isPresent() && row.get().isFresh(now)) {
            Optional<List<FlightLeg>> legs = read(row.get());
            if (legs.isPresent()) {
                memory.put(key, new CachedLegs(legs.get(), row.get().getExpiresAt()));
                return legs.get();
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
            memory.put(key, new CachedLegs(legs, now.plus(NOT_FOUND_TTL)));
            throw new BusinessException(ErrorCode.FLIGHT_NOT_FOUND);
        }
        Instant expiresAt = now.plus(ttl(legs, now));
        memory.put(key, new CachedLegs(legs, expiresAt));
        save(row, key, legs, now, expiresAt);
        return legs;
    }

    /** 가는 편: 도착 공항이 도시 공항 중 하나인 첫 구간. */
    public Optional<FlightLeg> findArrivingAt(List<FlightLeg> legs, Collection<String> airportIatas) {
        return legs.stream().filter(leg -> airportIatas.contains(leg.arrival().iata())).findFirst();
    }

    /** 오는 편: 출발 공항이 도시 공항 중 하나인 첫 구간. */
    public Optional<FlightLeg> findDepartingFrom(List<FlightLeg> legs, Collection<String> airportIatas) {
        return legs.stream().filter(leg -> airportIatas.contains(leg.departure().iata())).findFirst();
    }

    static Duration ttl(List<FlightLeg> legs, Instant now) {
        Instant earliestDeparture = legs.stream()
                .map(leg -> leg.departure().effectiveTime())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        boolean departsSoon = !earliestDeparture.isBefore(now) && !earliestDeparture.isAfter(now.plus(NEAR_DEPARTURE));
        return departsSoon ? SHORT_TTL : LONG_TTL;
    }

    private List<FlightLeg> staleOrUnavailable(Optional<FlightCacheEntry> row, FlightProviderException cause) {
        return row.flatMap(this::read).orElseThrow(() -> cause != null
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

    private static List<FlightLeg> await(CompletableFuture<List<FlightLeg>> running) {
        try {
            return running.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static List<FlightLeg> orNotFound(List<FlightLeg> legs) {
        if (legs.isEmpty()) {
            throw new BusinessException(ErrorCode.FLIGHT_NOT_FOUND);
        }
        return legs;
    }

    private record CacheKey(String flightNo, LocalDate date) {
    }

    private record CachedLegs(List<FlightLeg> legs, Instant expiresAt) {

        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
