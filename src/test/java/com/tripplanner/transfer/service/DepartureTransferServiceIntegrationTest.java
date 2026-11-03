package com.tripplanner.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.transfer.api.DepartureTransferResponse;
import com.tripplanner.transfer.api.DepartureTransferResponse.Departure;
import com.tripplanner.transfer.api.DepartureTransferResponse.DepartureLabel;
import com.tripplanner.transfer.api.DepartureTransferResponse.Option;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.api.ManualOverride;
import com.tripplanner.trip.service.TripService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** test 프로필(external.mock=true) + Testcontainers MySQL 시드(V100~V103)로 지역→공항 시각표와 귀국일 교통편을 검증한다. */
@Tag("integration")
@SpringBootTest
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class DepartureTransferServiceIntegrationTest {

    /** 금요일 ~ 월요일 */
    private static final LocalDate START = LocalDate.of(2030, 3, 15);
    private static final LocalDate MONDAY = LocalDate.of(2030, 3, 18);
    private static final long NAMBA = 11L;
    private static final long TENNOJI = 14L;

    /** 조사 보고서(.omc/research/seed-routes-201-207.md)의 노선·요일 유형별 {편수, 첫차 분, 막차 분} */
    private static final Map<String, List<Integer>> EXPECTED_TIMETABLE = Map.ofEntries(
            Map.entry("201:WEEKDAY", List.of(33, 6 * 60, 22 * 60)),
            Map.entry("201:SATURDAY", List.of(33, 6 * 60, 22 * 60)),
            Map.entry("201:HOLIDAY", List.of(33, 6 * 60, 22 * 60)),
            Map.entry("202:WEEKDAY", List.of(64, 5 * 60 + 15, 23 * 60 + 2)),
            Map.entry("202:SATURDAY", List.of(64, 5 * 60 + 15, 22 * 60 + 55)),
            Map.entry("202:HOLIDAY", List.of(64, 5 * 60 + 15, 22 * 60 + 55)),
            Map.entry("203:WEEKDAY", List.of(84, 4 * 60 + 50, 21 * 60 + 35)),
            Map.entry("203:SATURDAY", List.of(84, 4 * 60 + 50, 21 * 60 + 35)),
            Map.entry("203:HOLIDAY", List.of(84, 4 * 60 + 50, 21 * 60 + 35)),
            Map.entry("204:WEEKDAY", List.of(58, 6 * 60 + 4, 22 * 60 + 24)),
            Map.entry("204:SATURDAY", List.of(59, 6 * 60 + 12, 22 * 60 + 24)),
            Map.entry("204:HOLIDAY", List.of(59, 6 * 60 + 12, 22 * 60 + 24)),
            Map.entry("205:WEEKDAY", List.of(30, 6 * 60 + 16, 20 * 60 + 58)),
            Map.entry("205:SATURDAY", List.of(30, 6 * 60 + 16, 20 * 60 + 58)),
            Map.entry("205:HOLIDAY", List.of(30, 6 * 60 + 16, 20 * 60 + 58)),
            Map.entry("206:WEEKDAY", List.of(67, 5 * 60 + 25, 22 * 60 + 45)),
            Map.entry("206:SATURDAY", List.of(65, 5 * 60 + 26, 22 * 60 + 45)),
            Map.entry("206:HOLIDAY", List.of(65, 5 * 60 + 26, 22 * 60 + 45)));

    @Autowired
    TripService tripService;

    @Autowired
    DepartureTransferService departureTransferService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V101이 적용되어 201~206의 요일 유형별 편수·첫차·막차가 공식 시각표와 같다")
    void timetableMatchesResearch() {
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '101' and success = true", Integer.class))
                .isEqualTo(1);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select route_id, day_type, count(*) c, min(departure_min) f, max(departure_min) l
                from transport_timetable where route_id between 201 and 207
                group by route_id, day_type
                """);

        assertThat(rows).hasSameSizeAs(EXPECTED_TIMETABLE.entrySet());
        for (Map<String, Object> row : rows) {
            String key = row.get("route_id") + ":" + row.get("day_type");
            assertThat(List.of(((Number) row.get("c")).intValue(), ((Number) row.get("f")).intValue(),
                    ((Number) row.get("l")).intValue())).as(key).isEqualTo(EXPECTED_TIMETABLE.get(key));
        }
    }

    @Test
    @DisplayName("V101·V102 적용 후 201~206은 TIMETABLE·공식 출처이고 207·107은 비활성, 택시는 소요·메모가 고쳐지고 나머지 공항→지역 노선은 그대로다")
    void routeAttributes() {
        assertThat(jdbcTemplate.queryForList("""
                select concat(id, ':', schedule_type, ':', duration_min) from transport_route
                where id between 201 and 206 and first_departure_min is null and last_departure_min is null
                  and headway_min is null and active = true
                  and data_as_of = if(id in (201, 202), '2024-12-21', '2026-11-03')
                order by id
                """, String.class))
                .containsExactly("201:TIMETABLE:38", "202:TIMETABLE:45", "203:TIMETABLE:67", "204:TIMETABLE:79",
                        "205:TIMETABLE:66", "206:TIMETABLE:59");
        assertThat(jdbcTemplate.queryForObject("select board_stop from transport_route where id = 203", String.class))
                .contains("한큐산방가이");
        assertThat(jdbcTemplate.queryForList("select note from transport_route where id in (201, 202)", String.class))
                .allSatisfy(note -> assertThat(note).contains("2024/12/21").contains("확인 필요"));
        assertThat(jdbcTemplate.queryForList("select note from transport_route where id between 201 and 206",
                String.class)).allSatisfy(note -> assertThat(note).contains("공식 시간표 기준").contains("2026-11-03"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transport_route where id = 207 and active = false and note like '운휴%'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "select duration_min from transport_route where id between 208 and 211 order by id", Integer.class))
                .containsExactly(50, 67, 67, 45);
        assertThat(jdbcTemplate.queryForList("select note from transport_route where id between 208 and 211",
                String.class)).allSatisfy(note -> assertThat(note).startsWith("추정").contains("소요").contains("요금"));
        assertThat(jdbcTemplate.queryForObject("select note from transport_route where id = 201", String.class))
                .contains("이전 시드값").contains("34분").contains("편별 소요 미확인").doesNotContain("보수값");
        assertThat(jdbcTemplate.queryForObject("select note from transport_route where id = 210", String.class))
                .contains("빌려 쓴 추정값").contains("신오사카 출발 버스는 시드에 없음");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transport_route where id = 107 and active = false and note like '운휴%'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from transport_route where id < 200 and id <> 107 and active = true and data_as_of = '2026-10-01'",
                Integer.class)).isEqualTo(10);
    }

    @Test
    @DisplayName("월요일 18시 출발 난바 여행이면 실제 시드로 공항급행 15:08이 가장 늦은 편이고 그 순서로 202·201·208이 나온다")
    void departureAt1800FromSeed() {
        CreateTripResponse trip = createTrip(NAMBA, "2030-03-18T18:00:00+09:00");

        DepartureTransferResponse response = departureTransferService.departure(trip.tripId(), trip.ownerToken(), 120);

        assertThat(response.flightDepartureAt()).isEqualTo(time("2030-03-18T18:00:00+09:00"));
        assertThat(response.airportDeadline()).isEqualTo(time("2030-03-18T16:00:00+09:00"));
        assertThat(response.windowStart()).isEqualTo(time("2030-03-18T14:30:00+09:00"));
        assertThat(response.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(response.firstTrainTooLate()).isFalse();
        assertThat(response.latestLeaveAt()).isEqualTo(time("2030-03-18T14:53:00+09:00"));
        assertThat(response.boardingMarginMin()).isEqualTo(10);
        assertThat(response.revisedDepartureAt()).isNull();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(202L, 201L, 208L);

        assertThat(response.options().getFirst().departures())
                .extracting(Departure::departAt, Departure::arriveAirportAt, Departure::label)
                .containsExactly(
                        tuple(time("2030-03-18T13:53:00+09:00"), time("2030-03-18T14:38:00+09:00"), null),
                        tuple(time("2030-03-18T14:08:00+09:00"), time("2030-03-18T14:53:00+09:00"), null),
                        tuple(time("2030-03-18T14:23:00+09:00"), time("2030-03-18T15:08:00+09:00"), null),
                        tuple(time("2030-03-18T14:38:00+09:00"), time("2030-03-18T15:23:00+09:00"), null),
                        tuple(time("2030-03-18T14:53:00+09:00"), time("2030-03-18T15:38:00+09:00"),
                                DepartureLabel.SAFER),
                        tuple(time("2030-03-18T15:08:00+09:00"), time("2030-03-18T15:53:00+09:00"),
                                DepartureLabel.LATEST));
        assertThat(response.options().get(1).departures())
                .extracting(Departure::departAt, Departure::label)
                .containsExactly(
                        tuple(time("2030-03-18T14:05:00+09:00"), null),
                        tuple(time("2030-03-18T14:35:00+09:00"), DepartureLabel.SAFER),
                        tuple(time("2030-03-18T15:05:00+09:00"), DepartureLabel.LATEST));

        Option taxi = response.options().getLast();
        assertThat(taxi.is24h()).isTrue();
        assertThat(taxi.leaveByAt()).isEqualTo(time("2030-03-18T15:10:00+09:00"));
        assertThat(taxi.fare().display()).isEqualTo("¥17,000~20,000");
    }

    @Test
    @DisplayName("텐노지 여행에는 비활성화된 207 리무진이 나오지 않는다")
    void inactiveRouteNotReturned() {
        CreateTripResponse trip = createTrip(TENNOJI, "2030-03-18T18:00:00+09:00");

        DepartureTransferResponse response = departureTransferService.departure(trip.tripId(), trip.ownerToken(), 120);

        assertThat(response.options()).extracting(Option::routeId).containsExactly(206L, 211L);
    }

    @Test
    @DisplayName("월요일 7시 출발이면 실제 시드의 첫차로 마감(05:00)을 맞출 수 없어 firstTrainTooLate이다")
    void departureAt0700FromSeed() {
        CreateTripResponse trip = createTrip(NAMBA, "2030-03-18T07:00:00+09:00");

        DepartureTransferResponse response = departureTransferService.departure(trip.tripId(), trip.ownerToken(), 120);

        assertThat(response.firstTrainTooLate()).isTrue();
        assertThat(response.latestLeaveAt()).isNull();
        assertThat(response.options().getLast().leaveByAt()).isEqualTo(time("2030-03-18T04:10:00+09:00"));
    }

    @Test
    @DisplayName("월요일 공휴일(2026-11-23) 다음 날 5시 출발이면 실제 시드의 공항급행 막차는 휴일표 22:55다")
    void holidayLastTrainFromSeed() {
        CreateTripResponse trip = tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", NAMBA,
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 24),
                new ManualOverride(null, null, time("2026-11-24T05:00:00+09:00"), "KIX")));

        DepartureTransferResponse response = departureTransferService.departure(trip.tripId(), trip.ownerToken(), 120);

        assertThat(response.options().getFirst().routeId()).isEqualTo(202L);
        assertThat(response.options().getFirst().departures())
                .extracting(Departure::departAt, Departure::label, Departure::outsideWindow)
                .containsExactly(tuple(time("2026-11-23T22:55:00+09:00"), DepartureLabel.LATEST, true));
    }

    @Test
    @DisplayName("지연 fixture(TW9902, 예정 18:00 → 19:30) 편이면 저장 후에도 예정 시각으로 마감을 잡고 지연 시각은 안내만 한다")
    void delayedFlightFromFixture() {
        CreateTripResponse trip = tripService.create(new CreateTripRequest("KE723", "TW9902", "OSA", NAMBA, START,
                MONDAY, null));

        DepartureTransferResponse response = departureTransferService.departure(trip.tripId(), trip.ownerToken(), 120);

        assertThat(response.flightDepartureAt()).isEqualTo(time("2030-03-18T18:00:00+09:00"));
        assertThat(response.revisedDepartureAt()).isEqualTo(time("2030-03-18T19:30:00+09:00"));
        assertThat(response.airportDeadline()).isEqualTo(time("2030-03-18T16:00:00+09:00"));
    }

    private CreateTripResponse createTrip(long areaId, String returnDepartureAt) {
        return tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", areaId, START, MONDAY,
                new ManualOverride(null, null, time(returnDepartureAt), "KIX")));
    }

    private static OffsetDateTime time(String iso) {
        return OffsetDateTime.parse(iso);
    }
}
