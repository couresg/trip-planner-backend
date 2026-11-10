package com.tripplanner.trip.service;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.service.FlightService;
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
            if (snapshot.source() == FlightSource.MANUAL) {
                results.put(snapshot.direction(), FlightCheckResult.MANUAL);
            } else if (!inWindow(snapshot, clock.instant())) {
                results.put(snapshot.direction(), FlightCheckResult.OUT_OF_WINDOW);
            } else {
                Optional<FlightLeg> leg = lookup(snapshot);
                results.put(snapshot.direction(), leg.isPresent() ? FlightCheckResult.REFRESHED
                        : FlightCheckResult.FAILED);
                leg.ifPresent(l -> fetched.put(snapshot.direction(), new Fetched(snapshot, l, clock.instant())));
            }
        }
        if (!fetched.isEmpty()) {
            writeTx.executeWithoutResult(status -> fetched.values().forEach(this::apply));
        }
        return new FlightRefresh(results);
    }

    static boolean inWindow(Snapshot snapshot, Instant now) {
        Instant from = snapshot.scheduledDep().minus(BEFORE_DEPARTURE);
        Instant until = snapshot.direction() == FlightDirection.OUTBOUND
                ? later(snapshot.scheduledArr(), snapshot.revisedArr()).plus(AFTER_ARRIVAL)
                : later(snapshot.scheduledDep(), snapshot.revisedDep());
        return !now.isBefore(from) && !now.isAfter(until);
    }

    private Optional<FlightLeg> lookup(Snapshot snapshot) {
        List<FlightLeg> legs;
        try {
            legs = flightService.lookup(FlightNumber.of(snapshot.flightNo()), snapshot.flightDate());
        } catch (BusinessException e) {
            log.warn("저장된 항공편 재조회 실패: flightNo={}, date={}, code={}", snapshot.flightNo(),
                    snapshot.flightDate(), e.errorCode());
            return Optional.empty();
        }
        Optional<FlightLeg> leg = legs.stream()
                .filter(l -> l.departure().iata().equals(snapshot.depIata())
                        && l.arrival().iata().equals(snapshot.arrIata()))
                .findFirst();
        if (leg.isEmpty()) {
            log.warn("저장된 항공편과 공항이 같은 구간 없음: flightNo={}, date={}, {}-{}", snapshot.flightNo(),
                    snapshot.flightDate(), snapshot.depIata(), snapshot.arrIata());
        }
        return leg;
    }

    private void apply(Fetched fetched) {
        tripFlightRepository.lockById(fetched.snapshot().id()).ifPresent(flight -> {
            String before = flight.getStatus();
            Instant arrivalBefore = flight.arrivalAt();
            Instant departureBefore = flight.departureAt();
            if (flight.refresh(fetched.leg(), fetched.checkedAt())
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
            Instant revisedArr) {

        static Snapshot of(TripFlight flight) {
            return new Snapshot(flight.getId(), flight.getDirection(), flight.getSource(), flight.getFlightNo(),
                    flight.getFlightDate(), flight.getDepAirportIata(), flight.getArrAirportIata(),
                    flight.getScheduledDepUtc(), flight.getRevisedDepUtc(), flight.getScheduledArrUtc(),
                    flight.getRevisedArrUtc());
        }
    }

    private record Fetched(Snapshot snapshot, FlightLeg leg, Instant checkedAt) {
    }
}
