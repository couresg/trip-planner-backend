package com.tripplanner.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.domain.Area;
import com.tripplanner.catalog.domain.City;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.transfer.api.ArrivalTransferResponse;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Departure;
import com.tripplanner.transfer.api.ArrivalTransferResponse.DepartureStatus;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Option;
import com.tripplanner.transfer.domain.DayType;
import com.tripplanner.transfer.domain.RouteDirection;
import com.tripplanner.transfer.domain.Schedule;
import com.tripplanner.transfer.domain.ServiceMinute;
import com.tripplanner.transfer.domain.TransportMode;
import com.tripplanner.transfer.domain.TransportRoute;
import com.tripplanner.transfer.repository.TransportRouteRepository;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.service.TripService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 여행·노선 조회는 목, 시간표 전개는 실제 ScheduleExpander. 2026-11-05는 목요일(Asia/Tokyo).
 * 노선은 시드와 같은 값: 101 라피트(시각표 매시 05·35분, 06:35~23:05), 102 공항급행(05:45~23:35, 30분 간격), 108 택시.
 */
class ArrivalTransferServiceTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate START = LocalDate.of(2026, 11, 5);
    private static final LocalDate DATA_AS_OF = LocalDate.of(2026, 10, 1);

    private final TripService tripService = mock(TripService.class);
    private final AirportRepository airportRepository = mock(AirportRepository.class);
    private final TransportRouteRepository routeRepository = mock(TransportRouteRepository.class);
    private final List<TransportRoute> routes = new ArrayList<>();
    private ArrivalTransferService service;
    private City osaka;
    private Area namba;

    @BeforeEach
    void setUp() {
        osaka = mock(City.class);
        given(osaka.getId()).willReturn(1L);
        namba = mock(Area.class);
        given(namba.getId()).willReturn(11L);
        Airport kix = mock(Airport.class);
        given(kix.getId()).willReturn(1L);
        given(kix.getIata()).willReturn("KIX");
        given(kix.getTimezone()).willReturn("Asia/Tokyo");
        given(airportRepository.findAllByCityIdInOrderByIataAsc(List.of(1L))).willReturn(List.of(kix));
        given(routeRepository.findAllByAirportIdAndAreaIdAndDirectionAndActiveTrueOrderByIdAsc(1L, 11L,
                RouteDirection.AIRPORT_TO_AREA)).willReturn(routes);
        service = new ArrivalTransferService(tripService, airportRepository, routeRepository,
                new ScheduleExpander(), TransferProperties.defaults());
    }

    @Test
    @DisplayName("14시 도착이면 14:00~16:00 출발편을 보여주고 입국 완료(15:00) 전 출발은 탑승 어려움, 이후 첫 출발이 추천이다")
    void arrivalAt1400() {
        seedNambaRoutes();
        arriveAt(at(5, 14, 0));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.baseTime()).isEqualTo(offset(5, 14, 0));
        assertThat(response.windowStart()).isEqualTo(offset(5, 14, 0));
        assertThat(response.windowEnd()).isEqualTo(offset(5, 16, 0));
        assertThat(response.readyAt()).isEqualTo(offset(5, 15, 0));
        assertThat(response.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(response.noTransitWarning()).isFalse();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(101L, 102L, 108L);

        Option rapit = response.options().get(0);
        assertThat(rapit.departures()).containsExactly(
                departure(at(5, 14, 5), 43, DepartureStatus.HARD_TO_CATCH, false),
                departure(at(5, 14, 35), 43, DepartureStatus.HARD_TO_CATCH, false),
                departure(at(5, 15, 5), 43, DepartureStatus.AVAILABLE, true),
                departure(at(5, 15, 35), 43, DepartureStatus.AVAILABLE, false));
        assertThat(rapit.nextFirstDeparture()).isNull();
        assertThat(rapit.fare()).isEqualTo(new Fare(1490, null, null, "JPY", "¥1,490"));
        assertThat(rapit.is24h()).isFalse();
        assertThat(rapit.from()).isEqualTo("간사이공항역");
        assertThat(rapit.to()).isEqualTo("난바역");
        assertThat(rapit.durationMin()).isEqualTo(38);
        assertThat(rapit.walkAfterMin()).isEqualTo(5);
        assertThat(rapit.dataAsOf()).isEqualTo(DATA_AS_OF);
        assertThat(rapit.source()).isEqualTo("SEED");
        assertThat(rapit.note()).isEqualTo("note-101");

        assertThat(response.options().get(1).departures())
                .extracting(Departure::departAt, Departure::status, Departure::recommended)
                .containsExactly(
                        tuple(offset(5, 14, 15), DepartureStatus.HARD_TO_CATCH, false),
                        tuple(offset(5, 14, 45), DepartureStatus.HARD_TO_CATCH, false),
                        tuple(offset(5, 15, 15), DepartureStatus.AVAILABLE, true),
                        tuple(offset(5, 15, 45), DepartureStatus.AVAILABLE, false));

        Option taxi = response.options().get(2);
        assertThat(taxi.mode()).isEqualTo(TransportMode.TAXI);
        assertThat(taxi.is24h()).isTrue();
        assertThat(taxi.departures()).isEmpty();
        assertThat(taxi.nextFirstDeparture()).isNull();
        assertThat(taxi.fare()).isEqualTo(new Fare(null, 17000, 20000, "JPY", "¥17,000~20,000"));
    }

    @Test
    @DisplayName("입국 소요 시간을 30분으로 줄이면 추천이 더 이른 출발편으로 옮겨진다")
    void immigrationChangeMovesRecommended() {
        seedNambaRoutes();
        arriveAt(at(5, 14, 0));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 30);

        assertThat(response.readyAt()).isEqualTo(offset(5, 14, 30));
        assertThat(recommendedDepartAt(response.options().get(0))).containsExactly(offset(5, 14, 35));
        assertThat(recommendedDepartAt(response.options().get(1))).containsExactly(offset(5, 14, 45));

        ArrivalTransferResponse slower = service.arrival(TRIP_ID, TOKEN, 90);
        assertThat(recommendedDepartAt(slower.options().get(0))).containsExactly(offset(5, 15, 35));
        assertThat(recommendedDepartAt(slower.options().get(1))).containsExactly(offset(5, 15, 45));
    }

    @Test
    @DisplayName("입국 완료 시각과 정확히 같은 출발은 탑승 가능(AVAILABLE)이며 추천이다")
    void departureExactlyAtReadyAtIsAvailable() {
        seedNambaRoutes();
        arriveAt(at(5, 14, 5));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 30);

        assertThat(response.readyAt()).isEqualTo(offset(5, 14, 35));
        assertThat(response.options().get(0).departures()).first()
                .isEqualTo(departure(at(5, 14, 5), 43, DepartureStatus.HARD_TO_CATCH, false));
        assertThat(response.options().get(0).departures().get(1))
                .isEqualTo(departure(at(5, 14, 35), 43, DepartureStatus.AVAILABLE, true));
    }

    @Test
    @DisplayName("23시30분 도착이면 자정 넘어 01시30분까지의 출발편을 조회한다 (전날 운행일 1470분 포함)")
    void arrivalAt2330FindsAfterMidnightDepartures() {
        routes.add(route(301, TransportMode.BUS, "심야버스", 30, 0, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(1420, 1470, 1530, 1560)))));
        arriveAt(at(5, 23, 30));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.windowEnd()).isEqualTo(offset(6, 1, 30));
        assertThat(response.readyAt()).isEqualTo(offset(6, 0, 30));
        assertThat(response.noTransitWarning()).isFalse();
        assertThat(response.options().getFirst().departures()).containsExactly(
                departure(at(5, 23, 40), 30, DepartureStatus.HARD_TO_CATCH, false),
                departure(at(6, 0, 30), 30, DepartureStatus.AVAILABLE, true),
                departure(at(6, 1, 30), 30, DepartureStatus.AVAILABLE, false));
    }

    @Test
    @DisplayName("23시30분 도착이 막차 이후면 대중교통 경고와 함께 노선별 다음 날 첫차를 알려주고 택시는 마지막에 둔다")
    void arrivalAt2330AfterLastTrain() {
        seedNambaRoutes();
        arriveAt(at(5, 23, 30));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.windowStart()).isEqualTo(offset(5, 23, 30));
        assertThat(response.windowEnd()).isEqualTo(offset(6, 1, 30));
        assertThat(response.noTransitWarning()).isTrue();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(101L, 102L, 108L);
        assertThat(response.options()).extracting(Option::nextFirstDeparture)
                .containsExactly(offset(6, 6, 35), offset(6, 5, 45), null);
        assertThat(response.options()).allSatisfy(option -> assertThat(option.departures()).isEmpty());
        assertThat(response.options().get(2).is24h()).isTrue();
    }

    @Test
    @DisplayName("22시50분 도착이면 창 안 막차들이 모두 입국 완료(23:50) 전이라 탑승 어려움으로 남기고, 경고와 다음 날 첫차를 알려준다")
    void arrivalAt2250AllHardToCatch() {
        seedNambaRoutes();
        routes.add(route(103, TransportMode.BUS, "리무진버스", 50, 0, 1800, 1800, Schedule.headway(400, 1420, 30)));
        arriveAt(at(5, 22, 50));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.readyAt()).isEqualTo(offset(5, 23, 50));
        assertThat(response.windowEnd()).isEqualTo(offset(6, 0, 50));
        assertThat(response.noTransitWarning()).isTrue();
        assertThat(response.options()).extracting(Option::routeId, Option::nextFirstDeparture)
                .containsExactly(
                        tuple(101L, offset(6, 6, 35)),
                        tuple(102L, offset(6, 5, 45)),
                        tuple(103L, offset(6, 6, 40)),
                        tuple(108L, null));
        // 탑승 어려움 출발은 그대로 보여준다 (라피트 23:05, 공항급행 23:15, 버스 23:10·23:40)
        assertThat(response.options().get(0).departures())
                .containsExactly(departure(at(5, 23, 5), 43, DepartureStatus.HARD_TO_CATCH, false));
        assertThat(response.options().get(1).departures())
                .containsExactly(departure(at(5, 23, 15), 50, DepartureStatus.HARD_TO_CATCH, false));
        assertThat(response.options().get(2).departures()).extracting(Departure::departAt, Departure::status)
                .containsExactly(tuple(offset(5, 23, 10), DepartureStatus.HARD_TO_CATCH),
                        tuple(offset(5, 23, 40), DepartureStatus.HARD_TO_CATCH));
    }

    @Test
    @DisplayName("한 노선이라도 입국 완료 후 탈 수 있는 출발이 있으면 경고는 없고, 탈 수 있는 출발이 없는 노선만 다음 출발을 알려준다")
    void partialAvailability() {
        seedNambaRoutes();
        routes.add(route(301, TransportMode.BUS, "심야버스", 30, 0, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(1440)))));
        arriveAt(at(5, 22, 50));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.noTransitWarning()).isFalse();
        assertThat(response.options()).extracting(Option::routeId, Option::nextFirstDeparture)
                .containsExactly(
                        tuple(301L, null),
                        tuple(101L, offset(6, 6, 35)),
                        tuple(102L, offset(6, 5, 45)),
                        tuple(108L, null));
        assertThat(response.options().getFirst().departures())
                .containsExactly(departure(at(6, 0, 0), 30, DepartureStatus.AVAILABLE, true));
    }

    @Test
    @DisplayName("02시 도착이면 운행 중인 대중교통이 없어 경고를 띄우고 그날 첫차를 알려준다")
    void arrivalAt0200() {
        seedNambaRoutes();
        arriveAt(at(6, 2, 0));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.windowEnd()).isEqualTo(offset(6, 4, 0));
        assertThat(response.noTransitWarning()).isTrue();
        assertThat(response.options()).extracting(Option::routeId, Option::nextFirstDeparture)
                .containsExactly(
                        tuple(101L, offset(6, 6, 35)),
                        tuple(102L, offset(6, 5, 45)),
                        tuple(108L, null));
    }

    @Test
    @DisplayName("대중교통 노선은 추천 출발의 숙소 도착 시각이 빠른 순, 추천이 없는 노선은 그 뒤(노선 ID 순), 택시는 맨 뒤다")
    void ordering() {
        routes.add(route(90, TransportMode.TAXI, "택시", 50, 0, 17000, 20000, Schedule.none()));
        routes.add(route(91, TransportMode.RAIL, "느린 열차", 80, 5, 1000, 1000, Schedule.headway(345, 1415, 30)));
        routes.add(route(92, TransportMode.BUS, "빠른 버스", 20, 0, 1000, 1000, Schedule.headway(345, 1415, 30)));
        // 창 안 출발은 있지만 모두 탑승 어려움 → 추천 없음
        routes.add(route(93, TransportMode.BUS, "곧 끊기는 버스", 20, 0, 1000, 1000, Schedule.headway(345, 855, 30)));
        routes.add(route(94, TransportMode.RIDE_HAILING, "차량 호출", 50, 0, null, null, Schedule.none()));
        arriveAt(at(5, 14, 0));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        assertThat(response.options()).extracting(Option::routeId).containsExactly(92L, 91L, 93L, 90L, 94L);
        Option noRecommendation = response.options().get(2);
        assertThat(noRecommendation.departures()).isNotEmpty()
                .allSatisfy(d -> assertThat(d.recommended()).isFalse());
        // 입국 완료(15:00) 이후 출발이 없으므로 다음 날 첫차
        assertThat(noRecommendation.nextFirstDeparture()).isEqualTo(offset(6, 5, 45));
        assertThat(response.options().get(4).fare()).isNull();
    }

    @Test
    @DisplayName("대중교통 노선이 하나도 없으면 noTransitWarning이다")
    void onlyTaxi() {
        routes.add(route(108, TransportMode.TAXI, "택시", 50, 0, 17000, 20000, Schedule.none()));
        arriveAt(at(5, 14, 0));

        assertThat(service.arrival(TRIP_ID, TOKEN, 60).noTransitWarning()).isTrue();
    }

    @Test
    @DisplayName("여행 조회 실패(404·403)는 그대로 전달하고 노선을 조회하지 않는다")
    void tripLookupFailure() {
        given(tripService.findOwned(TRIP_ID, "wrong"))
                .willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP, "이 여행에 접근할 권한이 없습니다."));

        assertThatThrownBy(() -> service.arrival(TRIP_ID, "wrong", 60))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN_TRIP));
        verifyNoInteractions(routeRepository);
    }

    @Test
    @DisplayName("가는 편 도착 공항과 여행 숙소 지역의 공항→지역 노선을 조회한다")
    void queriesRoutesByAirportAndArea() {
        arriveAt(at(5, 14, 0));

        ArrivalTransferResponse response = service.arrival(TRIP_ID, TOKEN, 60);

        verify(routeRepository).findAllByAirportIdAndAreaIdAndDirectionAndActiveTrueOrderByIdAsc(1L, 11L,
                RouteDirection.AIRPORT_TO_AREA);
        assertThat(response.options()).isEmpty();
        assertThat(response.noTransitWarning()).isTrue();
    }

    // ===== helpers =====

    private void seedNambaRoutes() {
        routes.add(route(101, TransportMode.RAIL, "난카이 라피트", 38, 5, 1490, 1490,
                Schedule.timetable(Map.of(
                        DayType.WEEKDAY, every30(395, 1385),
                        DayType.SATURDAY, every30(395, 1385),
                        DayType.HOLIDAY, every30(395, 1385)))));
        routes.add(route(102, TransportMode.RAIL, "난카이 공항급행", 45, 5, 970, 970, Schedule.headway(345, 1415, 30)));
        routes.add(route(108, TransportMode.TAXI, "택시", 50, 0, 17000, 20000, Schedule.none()));
    }

    /** 수동 입력 가는 편 (도착 KIX). 도착 현지 날짜가 다음 날이어도 시작일은 START. */
    private void arriveAt(ZonedDateTime arrival) {
        Trip trip = new Trip("hash", osaka, namba, START, START.plusDays(3), CLOCK.instant());
        trip.addFlight(TripFlight.manualOutbound("KE723", START, "KIX", arrival.toInstant(), CLOCK.instant()));
        given(tripService.findOwned(TRIP_ID, TOKEN)).willReturn(trip);
    }

    private static TransportRoute route(long id, TransportMode mode, String name, int durationMin, int walkMin,
            Integer fareMin, Integer fareMax, Schedule schedule) {
        TransportRoute route = mock(TransportRoute.class);
        given(route.getId()).willReturn(id);
        given(route.getMode()).willReturn(mode);
        given(route.getNameKo()).willReturn(name);
        given(route.getBoardStop()).willReturn(mode.isOnDemand() ? "택시 승강장" : "간사이공항역");
        given(route.getAlightStop()).willReturn("난바역");
        given(route.getDurationMin()).willReturn(durationMin);
        given(route.getWalkMin()).willReturn(walkMin);
        given(route.getFareMin()).willReturn(fareMin);
        given(route.getFareMax()).willReturn(fareMax);
        given(route.getCurrency()).willReturn("JPY");
        given(route.is24h()).willReturn(mode.isOnDemand());
        given(route.getBoardingTip()).willReturn("tip-" + id);
        given(route.getNote()).willReturn("note-" + id);
        given(route.getDataAsOf()).willReturn(DATA_AS_OF);
        given(route.schedule()).willReturn(schedule);
        return route;
    }

    private static List<ServiceMinute> every30(int first, int last) {
        return IntStream.iterate(first, m -> m <= last, m -> m + 30).mapToObj(ServiceMinute::of).toList();
    }

    private static List<ServiceMinute> minutes(int... values) {
        return IntStream.of(values).mapToObj(ServiceMinute::of).toList();
    }

    private static List<OffsetDateTime> recommendedDepartAt(Option option) {
        return option.departures().stream().filter(Departure::recommended).map(Departure::departAt).toList();
    }

    private static Departure departure(ZonedDateTime departAt, int toAreaMin, DepartureStatus status,
            boolean recommended) {
        return new Departure(departAt.toOffsetDateTime(), departAt.plusMinutes(toAreaMin).toOffsetDateTime(), status,
                recommended);
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, TOKYO);
    }

    private static OffsetDateTime offset(int day, int hour, int minute) {
        return at(day, hour, minute).toOffsetDateTime();
    }
}
