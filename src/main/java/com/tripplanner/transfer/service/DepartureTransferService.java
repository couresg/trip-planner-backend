package com.tripplanner.transfer.service;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.api.DepartureTransferResponse;
import com.tripplanner.transfer.api.DepartureTransferResponse.Departure;
import com.tripplanner.transfer.api.DepartureTransferResponse.DepartureLabel;
import com.tripplanner.transfer.api.DepartureTransferResponse.Option;
import com.tripplanner.transfer.domain.RouteDirection;
import com.tripplanner.transfer.domain.Schedule;
import com.tripplanner.transfer.domain.TransportRoute;
import com.tripplanner.transfer.repository.TransportRouteRepository;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.service.TripService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 귀국일 교통편 (F3). deadline = D - 공항 도착 여유 (D = 오는 편 scheduled와 revised 중 이른 쪽).
 * 공항 도착이 window [deadline - 90분, deadline] 안인 편 중 노선마다 가장 늦은 편이 LATEST, 그 앞 편이 SAFER다.
 * window에 편이 없는 노선은 deadline - 240분까지 되돌아봐서 가장 늦은 한 편을 outsideWindow LATEST로 준다.
 * 숙소 출발(leaveAreaAt)이 가는 편 도착보다 이른 편은 라벨을 붙이기 전에 뺀다.
 * 대중교통 노선 중 window·되돌아보기에 남은 편이 있는 노선이 하나도 없으면 firstTrainTooLate다.
 * 택시·차량 호출의 leaveByAt은 착륙 전이면 null이고, firstTrainTooLate이면서 모두 null이면 noTimeAfterLanding이다.
 */
@Service
public class DepartureTransferService {

    private final TripService tripService;
    private final AirportRepository airportRepository;
    private final TransportRouteRepository routeRepository;
    private final ScheduleExpander scheduleExpander;
    private final TransferProperties properties;

    public DepartureTransferService(TripService tripService, AirportRepository airportRepository,
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
    public DepartureTransferResponse departure(String tripId, String ownerToken, int airportBufferMin) {
        return calculate(tripService.findOwned(tripId, ownerToken), airportBufferMin);
    }

    /** 소유자 확인을 마친 여행으로 계산한다. 일정 서비스가 호출자 트랜잭션 안에서 쓴다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public DepartureTransferResponse calculate(Trip trip, int airportBufferMin) {
        TripFlight returnFlight = trip.flight(FlightDirection.RETURN);
        Airport airport = TripAirports.find(airportRepository, trip, returnFlight.getDepAirportIata());
        ZoneId zone = ZoneId.of(airport.getTimezone());
        Instant landedAt = trip.flight(FlightDirection.OUTBOUND).arrivalAt();

        ZonedDateTime flightDeparture = returnFlight.earliestDepartureAt().atZone(zone);
        ZonedDateTime deadline = flightDeparture.minusMinutes(airportBufferMin);
        ZonedDateTime windowStart = deadline.minusMinutes(properties.departureWindowMin());
        ZonedDateTime lookbackStart = deadline.minusMinutes(properties.departureLookbackMin());

        List<RankedOption> ranked = new ArrayList<>();
        for (TransportRoute route : routeRepository.findAllByAirportIdAndAreaIdAndDirectionAndActiveTrueOrderByIdAsc(
                airport.getId(), trip.getArea().getId(), RouteDirection.AREA_TO_AIRPORT)) {
            ranked.add(route.getMode().isOnDemand()
                    ? onDemand(route, deadline, landedAt)
                    : transit(route, lookbackStart, windowStart, deadline, landedAt));
        }
        List<Option> options = ranked.stream()
                .sorted(Comparator.comparing(RankedOption::onDemand)
                        .thenComparing(RankedOption::latestLeaveAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(r -> r.option().routeId()))
                .map(RankedOption::option)
                .toList();
        OffsetDateTime latestLeaveAt = ranked.stream()
                .map(RankedOption::latestLeaveAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        boolean firstTrainTooLate = latestLeaveAt == null;
        List<RankedOption> onDemand = ranked.stream().filter(RankedOption::onDemand).toList();
        boolean noTimeAfterLanding = firstTrainTooLate && !onDemand.isEmpty()
                && onDemand.stream().allMatch(r -> r.option().leaveByAt() == null);

        return new DepartureTransferResponse(flightDeparture.toOffsetDateTime(),
                laterRevised(returnFlight, zone), deadline.toOffsetDateTime(), windowStart.toOffsetDateTime(),
                zone.getId(), properties.boardingMarginMin(), firstTrainTooLate, noTimeAfterLanding, latestLeaveAt,
                options);
    }

    /** revised가 scheduled보다 늦을 때만 안내용으로 준다. */
    private static OffsetDateTime laterRevised(TripFlight flight, ZoneId zone) {
        Instant revised = flight.getRevisedDepUtc();
        return revised != null && revised.isAfter(flight.getScheduledDepUtc())
                ? revised.atZone(zone).toOffsetDateTime()
                : null;
    }

    private RankedOption transit(TransportRoute route, ZonedDateTime lookbackStart, ZonedDateTime windowStart,
            ZonedDateTime deadline, Instant landedAt) {
        Schedule schedule = route.schedule();
        int rideMin = route.getDurationMin();
        List<ZonedDateTime> inWindow = afterLanding(route, scheduleExpander.departures(schedule,
                windowStart.minusMinutes(rideMin), deadline.minusMinutes(rideMin)), landedAt);
        List<Departure> departures = new ArrayList<>();
        if (inWindow.isEmpty()) {
            // 착륙 전 제외 전에도 window가 비었다면 도착이 windowStart와 같은 편은 없고, 제외로 비었다면 되돌아보기 편도 모두 착륙 전이다.
            List<ZonedDateTime> earlier = afterLanding(route, scheduleExpander.departures(schedule,
                    lookbackStart.minusMinutes(rideMin), windowStart.minusMinutes(rideMin)), landedAt);
            if (!earlier.isEmpty()) {
                departures.add(departure(route, earlier.getLast(), DepartureLabel.LATEST, true));
            }
        } else {
            int latest = inWindow.size() - 1;
            for (int i = 0; i <= latest; i++) {
                DepartureLabel label = i == latest ? DepartureLabel.LATEST
                        : i == latest - 1 ? DepartureLabel.SAFER : null;
                departures.add(departure(route, inWindow.get(i), label, false));
            }
        }
        OffsetDateTime latestLeaveAt = departures.isEmpty() ? null : departures.getLast().leaveAreaAt();
        return new RankedOption(option(route, departures, null), false, latestLeaveAt);
    }

    private List<ZonedDateTime> afterLanding(TransportRoute route, List<ZonedDateTime> departures, Instant landedAt) {
        return departures.stream()
                .filter(departAt -> !leaveAreaAt(route, departAt).toInstant().isBefore(landedAt))
                .toList();
    }

    private ZonedDateTime leaveAreaAt(TransportRoute route, ZonedDateTime departAt) {
        return departAt.minusMinutes(route.getWalkMin() + properties.boardingMarginMin());
    }

    private Departure departure(TransportRoute route, ZonedDateTime departAt, DepartureLabel label,
            boolean outsideWindow) {
        return new Departure(leaveAreaAt(route, departAt).toOffsetDateTime(),
                departAt.toOffsetDateTime(), departAt.plusMinutes(route.getDurationMin()).toOffsetDateTime(), label,
                outsideWindow);
    }

    private static RankedOption onDemand(TransportRoute route, ZonedDateTime deadline, Instant landedAt) {
        ZonedDateTime leaveBy = deadline.minusMinutes(route.getDurationMin());
        return new RankedOption(option(route, List.of(), leaveBy.toInstant().isBefore(landedAt) ? null : leaveBy), true,
                null);
    }

    private static Option option(TransportRoute route, List<Departure> departures, ZonedDateTime leaveBy) {
        return new Option(route.getId(), route.getMode(), route.getNameKo(), route.getBoardStop(),
                route.getAlightStop(), route.getDurationMin(), route.getWalkMin(),
                Fare.of(route.getFareMin(), route.getFareMax(), route.getCurrency()), route.getBoardingTip(),
                route.is24h(), departures, leaveBy == null ? null : leaveBy.toOffsetDateTime(),
                route.getDataAsOf(), ArrivalTransferService.SEED_SOURCE, route.getNote());
    }

    /** 정렬 키: 대중교통 먼저, LATEST 숙소 출발 시각 내림차순(편이 없으면 뒤), 노선 ID. */
    private record RankedOption(Option option, boolean onDemand, OffsetDateTime latestLeaveAt) {
    }
}
