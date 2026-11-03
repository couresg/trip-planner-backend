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
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightPoint;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.api.DepartureTransferResponse;
import com.tripplanner.transfer.api.DepartureTransferResponse.Departure;
import com.tripplanner.transfer.api.DepartureTransferResponse.DepartureLabel;
import com.tripplanner.transfer.api.DepartureTransferResponse.Option;
import com.tripplanner.transfer.domain.DayType;
import com.tripplanner.transfer.domain.RouteDirection;
import com.tripplanner.transfer.domain.Schedule;
import com.tripplanner.transfer.domain.ServiceMinute;
import com.tripplanner.transfer.domain.TransportMode;
import com.tripplanner.transfer.domain.TransportRoute;
import com.tripplanner.transfer.repository.TransportRouteRepository;
import com.tripplanner.trip.domain.FlightDirection;
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
 * 여행·노선 조회는 목, 시간표 전개는 실제 ScheduleExpander. 2026-11-05는 목요일, 11-06은 금요일, 11-07은 토요일(Asia/Tokyo).
 * 노선은 테스트 전용 값이다: 301 열차(09:00~20:00 매시 00·30분, 소요 40, 도보 5), 302 버스(08:15~20:15 매시 15·45분, 소요 60, 도보 0), 308 택시(소요 50).
 */
class DepartureTransferServiceTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DATA_AS_OF = LocalDate.of(2026, 11, 3);
    private static final int BOARDING_MARGIN = 10;

    private final TripService tripService = mock(TripService.class);
    private final AirportRepository airportRepository = mock(AirportRepository.class);
    private final TransportRouteRepository routeRepository = mock(TransportRouteRepository.class);
    private final List<TransportRoute> routes = new ArrayList<>();
    private DepartureTransferService service;
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
                RouteDirection.AREA_TO_AIRPORT)).willReturn(routes);
        service = new DepartureTransferService(tripService, airportRepository, routeRepository,
                new ScheduleExpander(), TransferProperties.defaults());
    }

    @Test
    @DisplayName("18시 출발이면 마감 16시, 14:30~16:00에 공항 도착하는 편을 보여주고 가장 늦은 편이 LATEST, 그 앞 편이 SAFER다")
    void departureAt1800() {
        seedRoutes();
        departAt(at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.flightDepartureAt()).isEqualTo(offset(5, 18, 0));
        assertThat(response.airportDeadline()).isEqualTo(offset(5, 16, 0));
        assertThat(response.windowStart()).isEqualTo(offset(5, 14, 30));
        assertThat(response.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(response.firstTrainTooLate()).isFalse();
        // 열차 15:00 출발(15:40 도착)이 LATEST, 도보 5분 + 승차 여유 10분 → 14:45. 버스 14:45 출발(도보 0) → 14:35보다 늦다.
        assertThat(response.latestLeaveAt()).isEqualTo(offset(5, 14, 45));
        assertThat(response.boardingMarginMin()).isEqualTo(10);
        assertThat(response.revisedDepartureAt()).isNull();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(301L, 302L, 308L);

        Option rail = response.options().getFirst();
        assertThat(rail.departures()).containsExactly(
                inWindow(at(5, 14, 0), 5, 40, null),
                inWindow(at(5, 14, 30), 5, 40, DepartureLabel.SAFER),
                inWindow(at(5, 15, 0), 5, 40, DepartureLabel.LATEST));
        assertThat(rail.leaveByAt()).isNull();
        assertThat(rail.from()).isEqualTo("난바역");
        assertThat(rail.to()).isEqualTo("간사이공항역");
        assertThat(rail.durationMin()).isEqualTo(40);
        assertThat(rail.walkBeforeMin()).isEqualTo(5);
        assertThat(rail.fare()).isEqualTo(new Fare(1490, null, null, "JPY", "¥1,490"));
        assertThat(rail.is24h()).isFalse();
        assertThat(rail.dataAsOf()).isEqualTo(DATA_AS_OF);
        assertThat(rail.source()).isEqualTo("SEED");
        assertThat(rail.note()).isEqualTo("note-301");

        assertThat(response.options().get(1).departures()).extracting(Departure::departAt, Departure::label)
                .containsExactly(
                        tuple(offset(5, 13, 45), null),
                        tuple(offset(5, 14, 15), DepartureLabel.SAFER),
                        tuple(offset(5, 14, 45), DepartureLabel.LATEST));

        Option taxi = response.options().get(2);
        assertThat(taxi.mode()).isEqualTo(TransportMode.TAXI);
        assertThat(taxi.departures()).isEmpty();
        assertThat(taxi.is24h()).isTrue();
        assertThat(taxi.leaveByAt()).isEqualTo(offset(5, 15, 10));
    }

    @Test
    @DisplayName("공항 도착 여유 150분·180분이면 마감과 window가 그만큼 앞당겨지고 LATEST가 옮겨진다")
    void airportBuffer() {
        seedRoutes();
        departAt(at(5, 18, 0));

        DepartureTransferResponse buffer150 = service.departure(TRIP_ID, TOKEN, 150);
        assertThat(buffer150.airportDeadline()).isEqualTo(offset(5, 15, 30));
        assertThat(buffer150.windowStart()).isEqualTo(offset(5, 14, 0));
        assertThat(latestDepartAt(buffer150.options().getFirst())).isEqualTo(offset(5, 14, 30));
        assertThat(buffer150.options().get(2).leaveByAt()).isEqualTo(offset(5, 14, 40));

        DepartureTransferResponse buffer180 = service.departure(TRIP_ID, TOKEN, 180);
        assertThat(buffer180.airportDeadline()).isEqualTo(offset(5, 15, 0));
        assertThat(buffer180.windowStart()).isEqualTo(offset(5, 13, 30));
        assertThat(latestDepartAt(buffer180.options().getFirst())).isEqualTo(offset(5, 14, 0));
        assertThat(buffer180.options().get(2).leaveByAt()).isEqualTo(offset(5, 14, 10));
    }

    @Test
    @DisplayName("10시 출발이면 마감 08시, 아침 첫 편들로 시간을 맞춘다")
    void departureAt1000() {
        routes.add(route(301, TransportMode.RAIL, "열차", 40, 5, 1490, 1490,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(360, 390, 420, 450)))));
        departAt(at(5, 10, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.airportDeadline()).isEqualTo(offset(5, 8, 0));
        assertThat(response.windowStart()).isEqualTo(offset(5, 6, 30));
        assertThat(response.firstTrainTooLate()).isFalse();
        // 06:00 → 06:40, 06:30 → 07:10, 07:00 → 07:40, 07:30 → 08:10(마감 뒤)
        assertThat(response.options().getFirst().departures()).containsExactly(
                inWindow(at(5, 6, 0), 5, 40, null),
                inWindow(at(5, 6, 30), 5, 40, DepartureLabel.SAFER),
                inWindow(at(5, 7, 0), 5, 40, DepartureLabel.LATEST));
        assertThat(response.latestLeaveAt()).isEqualTo(offset(5, 6, 45));
    }

    @Test
    @DisplayName("7시 출발이면 첫차로 마감을 맞출 수 없어 firstTrainTooLate이고 택시 출발 한계를 알려준다")
    void departureAt0700FirstTrainTooLate() {
        routes.add(route(301, TransportMode.RAIL, "열차", 40, 5, 1490, 1490,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(360, 1320)))));
        routes.add(taxi());
        departAt(at(6, 7, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.airportDeadline()).isEqualTo(offset(6, 5, 0));
        assertThat(response.windowStart()).isEqualTo(offset(6, 3, 30));
        assertThat(response.firstTrainTooLate()).isTrue();
        assertThat(response.latestLeaveAt()).isNull();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(301L, 308L);
        assertThat(response.options().getFirst().departures()).isEmpty();
        assertThat(response.options().getFirst().leaveByAt()).isNull();
        assertThat(response.options().get(1).leaveByAt()).isEqualTo(offset(6, 4, 10));
    }

    @Test
    @DisplayName("window 양 끝과 도착이 정확히 같은 편은 window 안이고, 1분이라도 벗어나면 빠진다")
    void windowBoundariesInclusive() {
        // 소요 30분, 마감 16:00, window 14:30~16:00. 14:00 → 14:30(시작), 15:30 → 16:00(마감)
        routes.add(route(301, TransportMode.RAIL, "열차", 30, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(13 * 60 + 59, 14 * 60, 15 * 60 + 30, 15 * 60 + 31)))));
        departAt(at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getFirst().departures()).containsExactly(
                inWindow(at(5, 14, 0), 5, 30, DepartureLabel.SAFER),
                inWindow(at(5, 15, 30), 5, 30, DepartureLabel.LATEST));
    }

    @Test
    @DisplayName("5시 출발이면 window(01:30~03:00)에 편이 없어 전날 23:02 공항급행을 window 밖 LATEST로 보여주고 경고는 없다")
    void lookbackFindsLastTrain() {
        routes.add(route(201, TransportMode.RAIL, "라피트", 38, 5, 1490, 1490,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(22 * 60)))));
        routes.add(route(202, TransportMode.RAIL, "공항급행", 45, 5, 970, 970,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(22 * 60 + 23, 23 * 60 + 2)))));
        routes.add(taxi());
        departAt(at(6, 5, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.airportDeadline()).isEqualTo(offset(6, 3, 0));
        assertThat(response.windowStart()).isEqualTo(offset(6, 1, 30));
        assertThat(response.firstTrainTooLate()).isFalse();
        assertThat(response.latestLeaveAt()).isEqualTo(offset(5, 22, 47));
        assertThat(response.options()).extracting(Option::routeId).containsExactly(202L, 201L, 308L);
        // 22:23 → 23:08도 되돌아보기 안이지만 가장 늦은 한 편만 준다
        assertThat(response.options().getFirst().departures()).containsExactly(new Departure(offset(5, 22, 47),
                offset(5, 23, 2), offset(5, 23, 47), DepartureLabel.LATEST, true));
        // 라피트 22:00 → 22:38은 되돌아보기 시작(23:00) 전이다
        assertThat(response.options().get(1).departures()).isEmpty();
    }

    @Test
    @DisplayName("되돌아보기 시작 시각과 도착이 같은 편은 포함하고, 그보다 이른 편은 뺀다")
    void lookbackBoundaryInclusive() {
        // 마감 03:00 → 되돌아보기 23:00부터. 22:20 + 40 = 23:00
        routes.add(route(301, TransportMode.RAIL, "경계 열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(22 * 60 + 20)))));
        routes.add(route(302, TransportMode.RAIL, "이른 열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(22 * 60 + 19)))));
        departAt(at(6, 5, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options()).extracting(Option::routeId).containsExactly(301L, 302L);
        assertThat(response.options().getFirst().departures()).extracting(Departure::label, Departure::outsideWindow)
                .containsExactly(tuple(DepartureLabel.LATEST, true));
        assertThat(response.options().get(1).departures()).isEmpty();
    }

    @Test
    @DisplayName("window에 편이 있는 노선은 되돌아보지 않고, window 밖 편은 아무 표시 없이 빠진다")
    void routeWithWindowDepartureDoesNotLookBack() {
        // 소요 40, 마감 16:00, window 14:30~16:00. 13:00 → 13:40은 window 밖
        routes.add(route(301, TransportMode.RAIL, "열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(13 * 60, 15 * 60)))));
        departAt(at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getFirst().departures())
                .containsExactly(inWindow(at(5, 15, 0), 5, 40, DepartureLabel.LATEST));
    }

    @Test
    @DisplayName("마감이 자정을 넘기면 금요일 운행일의 평일 시각표에서 토요일 새벽 편까지 찾는다")
    void deadlineCrossesMidnightUsesFridayServiceDay() {
        // 토 02:30 출발 → 마감 토 00:30. 금요일 운행일 1430분 = 금 23:50, 1450분 = 토 00:10
        routes.add(route(301, TransportMode.BUS, "심야버스", 20, 5, 1000, 1000,
                Schedule.timetable(Map.of(
                        DayType.WEEKDAY, minutes(1430, 1450),
                        DayType.SATURDAY, minutes(400)))));
        departAt(at(7, 2, 30));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.airportDeadline()).isEqualTo(offset(7, 0, 30));
        assertThat(response.windowStart()).isEqualTo(offset(6, 23, 0));
        assertThat(response.options().getFirst().departures()).containsExactly(
                inWindow(at(6, 23, 50), 5, 20, DepartureLabel.SAFER),
                inWindow(at(7, 0, 10), 5, 20, DepartureLabel.LATEST));
        assertThat(response.latestLeaveAt()).isEqualTo(offset(6, 23, 55));
    }

    @Test
    @DisplayName("window에 전날 운행일의 자정 넘김 편과 당일 운행일 편이 함께 있으면 각자 운행일의 요일 유형 시각표를 쓴다")
    void windowAcrossDayTypes() {
        // 토 06:00 출발 → 마감 토 04:00, window 토 02:30~04:00. 소요 30분이면 출발 02:00~03:30
        // 금요일 운행일 1570분 = 토 02:10, 토요일 운행일 190분 = 토 03:10
        routes.add(route(301, TransportMode.BUS, "심야버스", 30, 0, 1000, 1000,
                Schedule.timetable(Map.of(
                        DayType.WEEKDAY, minutes(1440 + 130),
                        DayType.SATURDAY, minutes(190),
                        DayType.HOLIDAY, minutes(200)))));
        departAt(at(7, 6, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getFirst().departures()).extracting(Departure::departAt, Departure::label)
                .containsExactly(
                        tuple(offset(7, 2, 10), DepartureLabel.SAFER),
                        tuple(offset(7, 3, 10), DepartureLabel.LATEST));
    }

    @Test
    @DisplayName("window 안 편이 하나뿐이면 LATEST만 있고 SAFER는 없다")
    void onlyOneDepartureHasNoSafer() {
        routes.add(route(301, TransportMode.RAIL, "열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(15 * 60)))));
        departAt(at(5, 18, 0));

        assertThat(service.departure(TRIP_ID, TOKEN, 120).options().getFirst().departures())
                .extracting(Departure::label, Departure::outsideWindow)
                .containsExactly(tuple(DepartureLabel.LATEST, false));
    }

    @Test
    @DisplayName("대중교통은 LATEST 숙소 출발이 늦은 순, 편이 없는 노선은 그 뒤(노선 ID 순), 택시·차량 호출은 맨 뒤다")
    void ordering() {
        routes.add(route(90, TransportMode.TAXI, "택시", 50, 0, 17000, 20000, Schedule.none()));
        routes.add(route(91, TransportMode.BUS, "편 없는 버스", 60, 0, 1000, 1000, Schedule.none()));
        routes.add(route(92, TransportMode.RAIL, "이른 열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(14 * 60)))));
        routes.add(route(93, TransportMode.RAIL, "늦은 열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(15 * 60)))));
        routes.add(route(94, TransportMode.RIDE_HAILING, "차량 호출", 50, 0, null, null, Schedule.none()));
        routes.add(route(95, TransportMode.BUS, "같은 시각 버스", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(15 * 60)))));
        routes.add(route(96, TransportMode.BUS, "또 편 없는 버스", 60, 0, 1000, 1000, Schedule.none()));
        departAt(at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options()).extracting(Option::routeId).containsExactly(93L, 95L, 92L, 91L, 96L, 90L, 94L);
        assertThat(response.options().getLast().fare()).isNull();
    }

    @Test
    @DisplayName("택시만 있으면 firstTrainTooLate이고 대중교통 출발 한계는 없다")
    void onlyTaxi() {
        routes.add(taxi());
        departAt(at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.firstTrainTooLate()).isTrue();
        assertThat(response.latestLeaveAt()).isNull();
    }

    @Test
    @DisplayName("지연된 revised는 계산에 쓰지 않고 예정 시각으로 마감을 잡으며 revisedDepartureAt으로만 알려준다")
    void laterRevisedIsNotUsed() {
        departWithRevised(at(5, 18, 0), at(5, 19, 30));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.flightDepartureAt()).isEqualTo(offset(5, 18, 0));
        assertThat(response.revisedDepartureAt()).isEqualTo(offset(5, 19, 30));
        assertThat(response.airportDeadline()).isEqualTo(offset(5, 16, 0));
    }

    @Test
    @DisplayName("앞당겨진 revised는 그 시각을 기준으로 계산하고 revisedDepartureAt은 없다")
    void earlierRevisedIsUsed() {
        departWithRevised(at(5, 18, 0), at(5, 17, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.flightDepartureAt()).isEqualTo(offset(5, 17, 0));
        assertThat(response.revisedDepartureAt()).isNull();
        assertThat(response.airportDeadline()).isEqualTo(offset(5, 15, 0));
    }

    @Test
    @DisplayName("revised가 예정과 같으면 revisedDepartureAt은 없다")
    void sameRevisedIsNotReported() {
        departWithRevised(at(5, 18, 0), at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.flightDepartureAt()).isEqualTo(offset(5, 18, 0));
        assertThat(response.revisedDepartureAt()).isNull();
    }

    @Test
    @DisplayName("월요일 공휴일(11-23) 밤은 휴일 시각표라 화요일 5시 출발의 막차는 23:02가 아니라 22:55다")
    void holidayEveningUsesHolidayTimetable() {
        addExpressRoute();
        departAt(at(24, 5, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getFirst().departures()).containsExactly(new Departure(offset(23, 22, 40),
                offset(23, 22, 55), offset(23, 23, 40), DepartureLabel.LATEST, true));
        assertThat(response.latestLeaveAt()).isEqualTo(offset(23, 22, 40));
    }

    @Test
    @DisplayName("공휴일 목록 밖인 해(2028)의 목요일 공휴일은 평일 시각표를 쓴다")
    void holidayOutsideListUsesWeekdayTimetable() {
        addExpressRoute();
        departAt(ZonedDateTime.of(2028, 11, 24, 5, 0, 0, 0, TOKYO));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getFirst().departures()).extracting(Departure::departAt)
                .containsExactly(ZonedDateTime.of(2028, 11, 23, 23, 2, 0, 0, TOKYO).toOffsetDateTime());
    }

    @Test
    @DisplayName("가는 편이 23:30에 착륙하고 다음 날 5시 출발이면 착륙 전 22:47 출발은 안내하지 않고 firstTrainTooLate이다")
    void excludesDeparturesBeforeLanding() {
        routes.add(route(202, TransportMode.RAIL, "공항급행", 45, 5, 970, 970,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(23 * 60 + 2)))));
        routes.add(taxi());
        departAt(at(6, 5, 0), at(5, 23, 30));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.firstTrainTooLate()).isTrue();
        assertThat(response.noTimeAfterLanding()).isFalse();
        assertThat(response.latestLeaveAt()).isNull();
        assertThat(response.options().getFirst().departures()).isEmpty();
        assertThat(response.options().getLast().leaveByAt()).isEqualTo(offset(6, 2, 10));
    }

    @Test
    @DisplayName("13시 착륙 후 같은 날 15:30 출발이면 대중교통과 택시 모두 착륙 전이라 택시 leaveByAt은 null이고 noTimeAfterLanding이다")
    void noTimeAfterLanding() {
        // 마감 13:30, 택시 50분 → 12:40. 라피트 12:50 출발 → 12:35 숙소 출발.
        routes.add(route(201, TransportMode.RAIL, "라피트", 38, 5, 1490, 1490,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(12 * 60 + 50)))));
        routes.add(taxi());
        departAt(at(5, 15, 30), at(5, 13, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.firstTrainTooLate()).isTrue();
        assertThat(response.noTimeAfterLanding()).isTrue();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(201L, 308L);
        assertThat(response.options().getLast().leaveByAt()).isNull();
    }

    @Test
    @DisplayName("택시 출발 한계가 착륙과 정확히 같으면 남겨 두고 noTimeAfterLanding이 아니다")
    void leaveByExactlyAtLandingIsKept() {
        routes.add(taxi());
        departAt(at(5, 15, 30), at(5, 12, 40));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getLast().leaveByAt()).isEqualTo(offset(5, 12, 40));
        assertThat(response.noTimeAfterLanding()).isFalse();
    }

    @Test
    @DisplayName("택시 하나라도 착륙 후에 출발할 수 있으면 noTimeAfterLanding이 아니고 늦은 택시만 null이다")
    void oneOnDemandStillPossible() {
        routes.add(route(90, TransportMode.TAXI, "먼 택시", 60, 0, 17000, 20000, Schedule.none()));
        routes.add(route(91, TransportMode.TAXI, "가까운 택시", 20, 0, 17000, 20000, Schedule.none()));
        departAt(at(5, 15, 30), at(5, 13, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.noTimeAfterLanding()).isFalse();
        assertThat(response.options()).extracting(Option::leaveByAt).containsExactly(null, offset(5, 13, 10));
    }

    @Test
    @DisplayName("대중교통 편이 남아 있으면 택시가 착륙 전이어도 noTimeAfterLanding이 아니다")
    void transitRemainsSoNotNoTime() {
        // 마감 13:30, 택시 50분 → 12:40(착륙 전). 열차 13:00 출발, 소요 20 → 13:20 도착, 숙소 12:45(착륙 12:45).
        routes.add(route(301, TransportMode.RAIL, "열차", 20, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(13 * 60)))));
        routes.add(taxi());
        departAt(at(5, 15, 30), at(5, 12, 45));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.firstTrainTooLate()).isFalse();
        assertThat(response.noTimeAfterLanding()).isFalse();
        assertThat(response.options().getLast().leaveByAt()).isNull();
    }

    @Test
    @DisplayName("착륙 필터는 입국 소요를 보지 않아 22:40 착륙이면 22:47 숙소 출발이 남는다")
    void landingFilterIgnoresImmigration() {
        routes.add(route(202, TransportMode.RAIL, "공항급행", 45, 5, 970, 970,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(23 * 60 + 2)))));
        departAt(at(6, 5, 0), at(5, 22, 40));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.latestLeaveAt()).isEqualTo(offset(5, 22, 47));
        assertThat(response.firstTrainTooLate()).isFalse();
    }

    @Test
    @DisplayName("착륙 전 편을 먼저 빼고 라벨을 붙이므로 SAFER가 착륙 전이면 사라지고, 숙소 출발이 착륙과 같은 시각인 편은 남는다")
    void labelsAreAssignedAfterLandingFilter() {
        // 소요 40, 도보 5, 여유 10. 15:00 출발 → 숙소 14:45, 14:30 출발 → 숙소 14:15. 착륙 14:45.
        routes.add(route(301, TransportMode.RAIL, "열차", 40, 5, 1000, 1000,
                Schedule.timetable(Map.of(DayType.WEEKDAY, minutes(14 * 60 + 30, 15 * 60)))));
        departAt(at(5, 18, 0), at(5, 14, 45));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        assertThat(response.options().getFirst().departures())
                .containsExactly(inWindow(at(5, 15, 0), 5, 40, DepartureLabel.LATEST));
        assertThat(response.firstTrainTooLate()).isFalse();
    }

    @Test
    @DisplayName("여행 조회 실패(404·403)는 그대로 전달하고 노선을 조회하지 않는다")
    void tripLookupFailure() {
        given(tripService.findOwned(TRIP_ID, "wrong"))
                .willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP, "이 여행에 접근할 권한이 없습니다."));

        assertThatThrownBy(() -> service.departure(TRIP_ID, "wrong", 120))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN_TRIP));
        verifyNoInteractions(routeRepository);
    }

    @Test
    @DisplayName("오는 편 출발 공항과 여행 숙소 지역의 지역→공항 노선을 조회한다")
    void queriesRoutesByAirportAndArea() {
        departAt(at(5, 18, 0));

        DepartureTransferResponse response = service.departure(TRIP_ID, TOKEN, 120);

        verify(routeRepository).findAllByAirportIdAndAreaIdAndDirectionAndActiveTrueOrderByIdAsc(1L, 11L,
                RouteDirection.AREA_TO_AIRPORT);
        assertThat(response.options()).isEmpty();
        assertThat(response.firstTrainTooLate()).isTrue();
        assertThat(response.noTimeAfterLanding()).isFalse();
    }

    // ===== helpers =====

    private void seedRoutes() {
        routes.add(route(301, TransportMode.RAIL, "열차", 40, 5, 1490, 1490,
                Schedule.timetable(Map.of(DayType.WEEKDAY, every30(9 * 60, 20 * 60)))));
        routes.add(route(302, TransportMode.BUS, "버스", 60, 0, 1800, 1800,
                Schedule.timetable(Map.of(DayType.WEEKDAY, every30(8 * 60 + 15, 20 * 60 + 15)))));
        routes.add(taxi());
    }

    private static TransportRoute taxi() {
        return route(308, TransportMode.TAXI, "택시", 50, 0, 17000, 20000, Schedule.none());
    }

    private void addExpressRoute() {
        routes.add(route(202, TransportMode.RAIL, "공항급행", 45, 5, 970, 970, Schedule.timetable(Map.of(
                DayType.WEEKDAY, minutes(23 * 60 + 2),
                DayType.HOLIDAY, minutes(22 * 60 + 55)))));
    }

    /** 수동 입력 오는 편 (출발 KIX). 가는 편은 사흘 전에 착륙했다. */
    private void departAt(ZonedDateTime departure) {
        departAt(departure, departure.minusDays(3));
    }

    private void departAt(ZonedDateTime departure, ZonedDateTime landedAt) {
        LocalDate end = departure.toLocalDate();
        Trip trip = tripLandedAt(landedAt, end);
        trip.addFlight(TripFlight.manualReturn("KE724", end, "KIX", departure.toInstant(), CLOCK.instant()));
        given(tripService.findOwned(TRIP_ID, TOKEN)).willReturn(trip);
    }

    /** 조회된 오는 편 (예정 + 변경 예상). */
    private void departWithRevised(ZonedDateTime scheduled, ZonedDateTime revised) {
        LocalDate end = scheduled.toLocalDate();
        FlightPoint departure = new FlightPoint("KIX", TOKYO, scheduled.toInstant(), revised.toInstant(), "1");
        FlightPoint arrival = new FlightPoint("ICN", ZoneId.of("Asia/Seoul"), scheduled.plusHours(2).toInstant(), null,
                "2");
        Trip trip = tripLandedAt(scheduled.minusDays(3), end);
        trip.addFlight(TripFlight.fromLeg(FlightDirection.RETURN, "KE724", end,
                new FlightLeg(departure, arrival, "Delayed"), CLOCK.instant()));
        given(tripService.findOwned(TRIP_ID, TOKEN)).willReturn(trip);
    }

    private Trip tripLandedAt(ZonedDateTime landedAt, LocalDate end) {
        LocalDate start = landedAt.toLocalDate();
        Trip trip = new Trip("hash", osaka, namba, start, end, CLOCK.instant());
        trip.addFlight(TripFlight.manualOutbound("KE723", start, "KIX", landedAt.toInstant(), CLOCK.instant()));
        return trip;
    }

    private static TransportRoute route(long id, TransportMode mode, String name, int durationMin, int walkMin,
            Integer fareMin, Integer fareMax, Schedule schedule) {
        TransportRoute route = mock(TransportRoute.class);
        given(route.getId()).willReturn(id);
        given(route.getMode()).willReturn(mode);
        given(route.getNameKo()).willReturn(name);
        given(route.getBoardStop()).willReturn("난바역");
        given(route.getAlightStop()).willReturn("간사이공항역");
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

    private static OffsetDateTime latestDepartAt(Option option) {
        return option.departures().stream().filter(d -> d.label() == DepartureLabel.LATEST).findFirst().orElseThrow()
                .departAt();
    }

    private static Departure inWindow(ZonedDateTime departAt, int walkMin, int rideMin, DepartureLabel label) {
        return new Departure(departAt.minusMinutes(walkMin + BOARDING_MARGIN).toOffsetDateTime(), departAt.toOffsetDateTime(),
                departAt.plusMinutes(rideMin).toOffsetDateTime(), label, false);
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, TOKYO);
    }

    private static OffsetDateTime offset(int day, int hour, int minute) {
        return at(day, hour, minute).toOffsetDateTime();
    }
}
