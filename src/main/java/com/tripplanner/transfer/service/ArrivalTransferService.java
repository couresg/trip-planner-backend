package com.tripplanner.transfer.service;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.transfer.api.ArrivalTransferResponse;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Departure;
import com.tripplanner.transfer.api.ArrivalTransferResponse.DepartureStatus;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Option;
import com.tripplanner.transfer.domain.RouteDirection;
import com.tripplanner.transfer.domain.Schedule;
import com.tripplanner.transfer.domain.TransportRoute;
import com.tripplanner.transfer.repository.TransportRouteRepository;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.service.TripService;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도착일 교통편 (F2). window = [A, A + 120분], readyAt = A + 입국 소요 시간 (A = 가는 편 도착 revised ?? scheduled).
 * readyAt보다 이른 출발은 HARD_TO_CATCH, 노선마다 첫 AVAILABLE 출발이 추천이다.
 * 대중교통 노선 중 window 안에 AVAILABLE 출발이 있는 노선이 하나도 없으면 noTransitWarning이고,
 * AVAILABLE 출발이 없는 노선은 readyAt 이후(같은 시각 포함) 다음 출발을 nextFirstDeparture로 준다.
 */
@Service
@EnableConfigurationProperties(TransferProperties.class)
public class ArrivalTransferService {

    /** 현재 노선은 모두 자체 시드 데이터다 (Google Routes 보강은 이후 단계). */
    static final String SEED_SOURCE = "SEED";

    private final TripService tripService;
    private final AirportRepository airportRepository;
    private final TransportRouteRepository routeRepository;
    private final ScheduleExpander scheduleExpander;
    private final TransferProperties properties;

    public ArrivalTransferService(TripService tripService, AirportRepository airportRepository,
            TransportRouteRepository routeRepository, ScheduleExpander scheduleExpander,
            TransferProperties properties) {
        this.tripService = tripService;
        this.airportRepository = airportRepository;
        this.routeRepository = routeRepository;
        this.scheduleExpander = scheduleExpander;
        this.properties = properties;
    }

    /** @throws com.tripplanner.common.exception.BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP */
    @Transactional(readOnly = true)
    public ArrivalTransferResponse arrival(String tripId, String ownerToken, int immigrationMin) {
        Trip trip = tripService.findOwned(tripId, ownerToken);
        TripFlight outbound = trip.flight(FlightDirection.OUTBOUND);
        Airport airport = arrivalAirport(trip, outbound);
        ZoneId zone = ZoneId.of(airport.getTimezone());

        ZonedDateTime base = outbound.arrivalAt().atZone(zone);
        ZonedDateTime windowEnd = base.plusMinutes(properties.arrivalWindowMin());
        ZonedDateTime readyAt = base.plusMinutes(immigrationMin);

        List<RankedOption> ranked = new ArrayList<>();
        boolean anyTransitAvailable = false;
        for (TransportRoute route : routeRepository.findAllByAirportIdAndAreaIdAndDirectionAndActiveTrueOrderByIdAsc(
                airport.getId(), trip.getArea().getId(), RouteDirection.AIRPORT_TO_AREA)) {
            if (route.getMode().isOnDemand()) {
                ranked.add(onDemand(route));
                continue;
            }
            RankedOption option = transit(route, base, windowEnd, readyAt);
            anyTransitAvailable |= option.recommendedArrival() != null;
            ranked.add(option);
        }
        List<Option> options = ranked.stream()
                .sorted(Comparator.comparing(RankedOption::onDemand)
                        .thenComparing(RankedOption::recommendedArrival,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(r -> r.option().routeId()))
                .map(RankedOption::option)
                .toList();

        return new ArrivalTransferResponse(base.toOffsetDateTime(), base.toOffsetDateTime(),
                windowEnd.toOffsetDateTime(), readyAt.toOffsetDateTime(), zone.getId(), !anyTransitAvailable,
                options);
    }

    /** 가는 편 도착 공항 (여행 생성 시 도시 공항임을 검증했다). */
    private Airport arrivalAirport(Trip trip, TripFlight outbound) {
        return airportRepository.findAllByCityIdInOrderByIataAsc(List.of(trip.getCity().getId())).stream()
                .filter(a -> a.getIata().equals(outbound.getArrAirportIata()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "여행 도시에 가는 편 도착 공항이 없음: " + outbound.getArrAirportIata()));
    }

    private RankedOption transit(TransportRoute route, ZonedDateTime windowStart, ZonedDateTime windowEnd,
            ZonedDateTime readyAt) {
        Schedule schedule = route.schedule();
        int toAreaMin = route.getDurationMin() + route.getWalkMin();
        List<Departure> departures = new ArrayList<>();
        Instant recommendedArrival = null;
        for (ZonedDateTime dep : scheduleExpander.departures(schedule, windowStart, windowEnd)) {
            ZonedDateTime arriveAreaAt = dep.plusMinutes(toAreaMin);
            boolean available = !dep.isBefore(readyAt);
            boolean recommended = available && recommendedArrival == null;
            if (recommended) {
                recommendedArrival = arriveAreaAt.toInstant();
            }
            departures.add(new Departure(dep.toOffsetDateTime(), arriveAreaAt.toOffsetDateTime(),
                    available ? DepartureStatus.AVAILABLE : DepartureStatus.HARD_TO_CATCH, recommended));
        }
        ZonedDateTime nextFirst = recommendedArrival == null
                ? scheduleExpander.nextDepartureFrom(schedule, readyAt).orElse(null)
                : null;
        return new RankedOption(option(route, departures, nextFirst), false, recommendedArrival);
    }

    private static RankedOption onDemand(TransportRoute route) {
        return new RankedOption(option(route, List.of(), null), true, null);
    }

    private static Option option(TransportRoute route, List<Departure> departures, ZonedDateTime nextFirst) {
        return new Option(route.getId(), route.getMode(), route.getNameKo(), route.getBoardStop(),
                route.getAlightStop(), route.getDurationMin(), route.getWalkMin(),
                Fare.of(route.getFareMin(), route.getFareMax(), route.getCurrency()), route.getBoardingTip(),
                route.is24h(), departures, nextFirst == null ? null : nextFirst.toOffsetDateTime(),
                route.getDataAsOf(), SEED_SOURCE, route.getNote());
    }

    /** 정렬 키: 대중교통 먼저, 추천 출발의 숙소 도착 시각 오름차순(추천 없으면 뒤), 노선 ID. */
    private record RankedOption(Option option, boolean onDemand, Instant recommendedArrival) {
    }
}
