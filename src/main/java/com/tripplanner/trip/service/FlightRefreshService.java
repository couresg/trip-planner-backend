package com.tripplanner.trip.service;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.service.FlightService;
import com.tripplanner.flight.service.FlightService.FlightLookup;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.FlightSource;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.repository.TripFlightRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 교통편·일정 계산 전에 여행의 저장된 항공편을 다시 조회해 trip_flight를 갱신한다 (지연·결항 반영).
 * 재조회 구간(양 끝 포함, 저장된 값 기준): 가는 편 [예정 출발 - 48시간, max(예정 도착, 변경 도착) + 3시간],
 * 오는 편 [예정 출발 - 48시간, max(예정 출발, 변경 출발)]. 수동 입력 편은 조회하지 않는다.
 *
 * 확인 시각(fetched_at)은 요청 시각이 아니라 그 값을 공급자에서 받은 시각이고, 더 나중에 받은 값만 저장한다.
 *
 * <p>순서: 읽기 트랜잭션(소유자 확인 + 스냅샷) → 트랜잭션 밖 FlightService 조회(캐시) → 짧은 쓰기 트랜잭션(행 잠금 후 갱신).
 * 외부 호출 동안 DB 커넥션·잠금을 잡지 않는다. 조회 실패는 요청을 실패시키지 않고 FAILED로 돌려준다.
 */
@Service
public class FlightRefreshService {

    private static final Logger log = LoggerFactory.getLogger(FlightRefreshService.class);

    static final Duration BEFORE_DEPARTURE = Duration.ofHours(48);
    static final Duration AFTER_ARRIVAL = Duration.ofHours(3);

    private final TripService tripService;
    private final TripFlightRepository tripFlightRepository;
    private final FlightService flightService;
    private final Clock clock;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public FlightRefreshService(TripService tripService, TripFlightRepository tripFlightRepository,
            FlightService flightService, Clock clock, PlatformTransactionManager transactionManager) {
        this.tripService = tripService;
        this.tripFlightRepository = tripFlightRepository;
        this.flightService = flightService;
        this.clock = clock;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /** @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP (외부 조회 전에 판정) */
    public FlightRefresh refresh(String tripId, String ownerToken, Set<FlightDirection> directions) {
        List<Snapshot> snapshots = readTx.execute(status -> tripService.findOwned(tripId, ownerToken).getFlights()
                .stream()
                .filter(flight -> directions.contains(flight.getDirection()))
                .map(Snapshot::of)
                .toList());

        Map<FlightDirection, FlightCheckResult> results = new EnumMap<>(FlightDirection.class);
        // EnumMap 순서(OUTBOUND → RETURN)로 잠가 동시 요청 간 잠금 순서를 고정한다.
        Map<FlightDirection, Fetched> fetched = new EnumMap<>(FlightDirection.class);
        for (Snapshot snapshot : snapshots) {
            // 수동 입력 편은 반대쪽 시각이 없어 구간을 계산하지 않는다.
            FlightCheckResult outside = snapshot.source() == FlightSource.MANUAL ? null
                    : window(snapshot, clock.instant());
            if (snapshot.source() == FlightSource.MANUAL) {
                results.put(snapshot.direction(), FlightCheckResult.MANUAL);
            } else if (outside != null) {
                results.put(snapshot.direction(), outside);
            } else {
                Optional<Fetched> result = lookup(snapshot);
                results.put(snapshot.direction(), result.isPresent() ? FlightCheckResult.REFRESHED
                        : FlightCheckResult.FAILED);
                // 저장된 값보다 나중에 받은 값일 때만 쓴다 (같은 캐시 값이면 UPDATE·잠금 없이 끝난다).
                result.filter(r -> r.fetchedAt().isAfter(snapshot.fetchedAt()))
                        .ifPresent(r -> fetched.put(snapshot.direction(), r));
            }
        }
        if (!fetched.isEmpty()) {
            writeTx.executeWithoutResult(status -> fetched.values().forEach(this::apply));
        }
        return new FlightRefresh(results);
    }

    /** 재조회 구간 안이면 null, 전이면 BEFORE_WINDOW, 지났으면 AFTER_WINDOW. */
    static FlightCheckResult window(Snapshot snapshot, Instant now) {
        Instant from = snapshot.scheduledDep().minus(BEFORE_DEPARTURE);
        Instant until = snapshot.direction() == FlightDirection.OUTBOUND
                ? later(snapshot.scheduledArr(), snapshot.revisedArr()).plus(AFTER_ARRIVAL)
                : later(snapshot.scheduledDep(), snapshot.revisedDep());
        if (now.isBefore(from)) {
            return FlightCheckResult.BEFORE_WINDOW;
        }
        return now.isAfter(until) ? FlightCheckResult.AFTER_WINDOW : null;
    }

    /** 만료된 DB 캐시 대체(공급자 장애)는 새 정보가 아니므로 실패로 본다. */
    private Optional<Fetched> lookup(Snapshot snapshot) {
        FlightLookup lookup;
        try {
            lookup = flightService.lookupWithMeta(FlightNumber.of(snapshot.flightNo()), snapshot.flightDate());
        } catch (BusinessException e) {
            log.warn("저장된 항공편 재조회 실패: flightNo={}, date={}, code={}", snapshot.flightNo(),
                    snapshot.flightDate(), e.errorCode());
            return Optional.empty();
        }
        if (lookup.stale()) {
            log.warn("저장된 항공편 재조회 실패(공급자 장애, 만료된 캐시만 있음): flightNo={}, date={}", snapshot.flightNo(),
                    snapshot.flightDate());
            return Optional.empty();
        }
        Optional<FlightLeg> leg = lookup.legs().stream()
                .filter(l -> l.departure().iata().equals(snapshot.depIata())
                        && l.arrival().iata().equals(snapshot.arrIata()))
                .findFirst();
        if (leg.isEmpty()) {
            log.warn("저장된 항공편과 공항이 같은 구간 없음: flightNo={}, date={}, {}-{}", snapshot.flightNo(),
                    snapshot.flightDate(), snapshot.depIata(), snapshot.arrIata());
        }
        return leg.map(l -> new Fetched(snapshot, l, lookup.fetchedAt()));
    }

    private void apply(Fetched fetched) {
        tripFlightRepository.lockById(fetched.snapshot().id()).ifPresent(flight -> {
            String before = flight.getStatus();
            Instant arrivalBefore = flight.arrivalAt();
            Instant departureBefore = flight.departureAt();
            if (flight.refresh(fetched.leg(), fetched.fetchedAt())
                    && (!before.equals(flight.getStatus()) || !arrivalBefore.equals(flight.arrivalAt())
                            || !departureBefore.equals(flight.departureAt()))) {
                log.info("저장된 항공편 갱신: flightNo={}, date={}, status {} -> {}", flight.getFlightNo(),
                        flight.getFlightDate(), before, flight.getStatus());
            }
        });
    }

    private static Instant later(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }

    /** 방향별 재조회 결과. 요청하지 않은 방향은 null. */
    public record FlightRefresh(Map<FlightDirection, FlightCheckResult> results) {

        public FlightRefresh {
            results = Map.copyOf(results);
        }

        public FlightCheckResult of(FlightDirection direction) {
            return results.get(direction);
        }
    }

    /** 트랜잭션 밖에서 쓰는 저장된 항공편 값 (엔티티를 트랜잭션 밖으로 들고 나가지 않는다). */
    record Snapshot(long id, FlightDirection direction, FlightSource source, String flightNo, LocalDate flightDate,
            String depIata, String arrIata, Instant scheduledDep, Instant revisedDep, Instant scheduledArr,
            Instant revisedArr, Instant fetchedAt) {

        static Snapshot of(TripFlight flight) {
            return new Snapshot(flight.getId(), flight.getDirection(), flight.getSource(), flight.getFlightNo(),
                    flight.getFlightDate(), flight.getDepAirportIata(), flight.getArrAirportIata(),
                    flight.getScheduledDepUtc(), flight.getRevisedDepUtc(), flight.getScheduledArrUtc(),
                    flight.getRevisedArrUtc(), flight.getFetchedAt());
        }
    }

    private record Fetched(Snapshot snapshot, FlightLeg leg, Instant fetchedAt) {
    }
}
