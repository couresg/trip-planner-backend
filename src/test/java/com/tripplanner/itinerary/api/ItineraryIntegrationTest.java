package com.tripplanner.itinerary.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tripplanner.itinerary.service.ItineraryService;
import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.transfer.api.ArrivalTransferResponse;
import com.tripplanner.transfer.domain.TransportMode;
import com.tripplanner.transfer.service.ArrivalTransferService;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.api.ManualOverride;
import com.tripplanner.trip.api.TripController;
import com.tripplanner.trip.service.TripService;
import com.tripplanner.trip.service.FlightCheckResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * test 프로필 + Testcontainers MySQL 시드로 일정 API 전체를 HTTP부터 DB까지 검증한다.
 * 2030-03-15 금요일 ~ 03-18 월요일, 난바(11). 장소: 501 오사카성 09:00~17:00(90분), 506 호젠지 상시 개방(45분), 517 미술관 월 휴관(90분).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class ItineraryIntegrationTest {

    private static final LocalDate START = LocalDate.of(2030, 3, 15);
    private static final LocalDate MONDAY = LocalDate.of(2030, 3, 18);
    private static final String D1 = "2030-03-15";
    private static final String D2 = "2030-03-16";
    private static final String D3 = "2030-03-17";
    private static final String D4 = "2030-03-18";
    private static final long NAMBA = 11L;
    private static final long CASTLE = 501L;
    private static final long HOZENJI = 506L;
    private static final long MUSEUM = 517L;
    private static final String MISSING_TRIP = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TripService tripService;

    @Autowired
    ArrivalTransferService arrivalTransferService;

    @Autowired
    ItineraryService itineraryService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("빈 일정도 시작일~종료일 모든 날짜를 dayIndex 1부터 돌려주고 숙소 출발 마감은 귀국일에만 있다")
    void emptyDaysIncluded() throws Exception {
        CreateTripResponse trip = createTrip();

        call(get(itinerary(trip)), trip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.returnMarginMin").value(30))
                .andExpect(jsonPath("$.days[*].date").value(contains(D1, D2, D3, D4)))
                .andExpect(jsonPath("$.days[*].dayIndex").value(contains(1, 2, 3, 4)))
                .andExpect(jsonPath("$.days[0].items").value(empty()))
                .andExpect(jsonPath("$.days[0].latestEnd").value(nullValue()))
                .andExpect(jsonPath("$.days[1].earliestStart").value(nullValue()))
                .andExpect(jsonPath("$.days[3].earliestStart").value(nullValue()))
                .andExpect(jsonPath("$.days[3].latestEnd").value("2030-03-18T14:53:00+09:00"));
    }

    @Test
    @DisplayName("earliestStart는 같은 입국 소요로 계산한 도착일 추천편의 가장 이른 숙소 도착 시각이고 시작일에만 있다")
    void earliestStartMatchesArrivalTab() throws Exception {
        CreateTripResponse trip = createTrip();

        for (int immigrationMin : List.of(30, 90)) {
            OffsetDateTime expected = arrivalTransferService
                    .arrival(trip.tripId(), trip.ownerToken(), immigrationMin, FlightCheckResult.BEFORE_WINDOW).options().stream()
                    .flatMap(option -> option.departures().stream())
                    .filter(ArrivalTransferResponse.Departure::recommended)
                    .map(ArrivalTransferResponse.Departure::arriveAreaAt)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();

            String body = call(get(itinerary(trip)).param("immigrationMin", String.valueOf(immigrationMin)), trip)
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(OffsetDateTime.parse(JsonPath.<String>read(body, "$.days[0].earliestStart")))
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("airportBufferMin을 바꾸면 귀국일 마감과 경고가 그 값으로 다시 계산된다")
    void bufferChangesLatestEnd() throws Exception {
        CreateTripResponse trip = createTrip();
        add(trip, HOZENJI, D4, "13:38");

        call(get(itinerary(trip)), trip)
                .andExpect(jsonPath("$.days[3].items[0].warnings").value(empty()));
        call(get(itinerary(trip)).param("airportBufferMin", "180"), trip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[3].latestEnd").value("2030-03-18T13:53:00+09:00"))
                .andExpect(jsonPath("$.days[3].items[0].warnings").value(contains("AFTER_DEPARTURE_LEAVE_TIME")));
    }

    @Test
    @DisplayName("허용되지 않은 immigrationMin·airportBufferMin은 여행 조회 전에 VALIDATION_FAILED다")
    void invalidTransferOptions() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/trips/" + MISSING_TRIP + "/itinerary").param("immigrationMin", "45")),
                400, "VALIDATION_FAILED");
        expectError(mockMvc.perform(get("/api/v1/trips/" + MISSING_TRIP + "/itinerary").param("airportBufferMin", "60")),
                400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("다음 날 00:20 착륙이면 추천편이 없어 입국 + 택시로 earliestStart가 둘째 날에 놓이고 시작일 항목은 BEFORE_ARRIVAL이다")
    void overnightArrival() throws Exception {
        CreateTripResponse trip = createTrip("2030-03-16T00:20:00+09:00", "2030-03-18T18:00:00+09:00");
        add(trip, HOZENJI, D1, null);
        add(trip, HOZENJI, D1, "23:00");
        add(trip, HOZENJI, D2, "01:00");
        add(trip, HOZENJI, D2, null);
        add(trip, HOZENJI, D2, "09:00");

        ArrivalTransferResponse arrival = arrivalTransferService.arrival(trip.tripId(), trip.ownerToken(), 60, FlightCheckResult.BEFORE_WINDOW);
        assertThat(arrival.options()).flatExtracting(ArrivalTransferResponse.Option::departures)
                .noneMatch(ArrivalTransferResponse.Departure::recommended);
        ArrivalTransferResponse.Option taxi = arrival.options().stream()
                .filter(option -> option.mode() == TransportMode.TAXI)
                .findFirst()
                .orElseThrow();
        OffsetDateTime expected = OffsetDateTime.parse("2030-03-16T01:20:00+09:00")
                .plusMinutes(taxi.durationMin() + taxi.walkAfterMin());

        String body = call(get(itinerary(trip)), trip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].earliestStart").value(nullValue()))
                .andExpect(jsonPath("$.days[0].items[0].warnings").value(contains("BEFORE_ARRIVAL")))
                .andExpect(jsonPath("$.days[0].items[1].warnings").value(contains("BEFORE_ARRIVAL")))
                .andExpect(jsonPath("$.days[1].items[0].warnings").value(contains("BEFORE_ARRIVAL")))
                .andExpect(jsonPath("$.days[1].items[1].warnings").value(empty()))
                .andExpect(jsonPath("$.days[1].items[2].warnings").value(empty()))
                .andReturn().getResponse().getContentAsString();
        assertThat(OffsetDateTime.parse(JsonPath.<String>read(body, "$.days[1].earliestStart"))).isEqualTo(expected);
    }

    @Test
    @DisplayName("이른 오는 편으로 택시도 착륙 전이 되면 latestEnd가 어느 날에도 없고 출발 경고도 없다")
    void noTimeAfterLanding() throws Exception {
        CreateTripResponse trip = tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", NAMBA, START,
                START, new ManualOverride(time("2030-03-15T23:00:00+09:00"), "KIX", time("2030-03-15T23:30:00+09:00"),
                        "KIX")));
        add(trip, HOZENJI, D1, "23:50");

        call(get(itinerary(trip)), trip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].latestEnd").value(nullValue()))
                .andExpect(jsonPath("$.days[0].items[0].warnings").value(contains("BEFORE_ARRIVAL")));
    }

    @Test
    @DisplayName("시드 영업시간과 교통편 시각으로 경고를 계산한다: 도착 전, 영업시간 밖, 체류가 닫는 시각을 넘김, 휴관일, 체류와 복귀 여유가 마감을 넘김, 경고 없음")
    void warningsFromSeed() throws Exception {
        CreateTripResponse trip = createTrip();
        add(trip, CASTLE, D1, "10:00");
        add(trip, CASTLE, D2, "08:59");
        add(trip, CASTLE, D2, "09:00");
        add(trip, CASTLE, D2, "15:30");
        add(trip, CASTLE, D2, "15:31");
        add(trip, MUSEUM, D4, "10:00");
        add(trip, CASTLE, D4, "14:00");
        add(trip, HOZENJI, D4, "13:00");
        add(trip, CASTLE, D3, null);

        call(get(itinerary(trip)), trip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].items[0].warnings").value(contains("BEFORE_ARRIVAL")))
                .andExpect(jsonPath("$.days[1].items[0].warnings").value(contains("OUTSIDE_OPENING_HOURS")))
                .andExpect(jsonPath("$.days[1].items[1].warnings").value(empty()))
                .andExpect(jsonPath("$.days[1].items[2].warnings").value(empty()))
                .andExpect(jsonPath("$.days[1].items[3].warnings").value(contains("OUTSIDE_OPENING_HOURS")))
                .andExpect(jsonPath("$.days[2].items[0].warnings").value(empty()))
                .andExpect(jsonPath("$.days[3].items[0].warnings").value(contains("OUTSIDE_OPENING_HOURS")))
                .andExpect(jsonPath("$.days[3].items[1].warnings").value(contains("AFTER_DEPARTURE_LEAVE_TIME")))
                .andExpect(jsonPath("$.days[3].items[2].warnings").value(empty()));
    }

    @Test
    @DisplayName("항목을 추가하면 201과 날짜 끝 위치·장소 정보를 돌려주고 같은 장소도 다시 추가할 수 있다")
    void addAppendsAtEnd() throws Exception {
        CreateTripResponse trip = createTrip();

        call(post(items(trip)).contentType(MediaType.APPLICATION_JSON).content("""
                {"placeId":501,"date":"2030-03-16","plannedTime":"10:30","memo":"천수각"}"""), trip)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.date").value(D2))
                .andExpect(jsonPath("$.position").value(0))
                .andExpect(jsonPath("$.plannedTime").value("10:30"))
                .andExpect(jsonPath("$.memo").value("천수각"))
                .andExpect(jsonPath("$.place.id").value(501))
                .andExpect(jsonPath("$.place.nameKo").value("오사카성"))
                .andExpect(jsonPath("$.place.category").value("SIGHT"))
                .andExpect(jsonPath("$.place.lat").value(34.6873))
                .andExpect(jsonPath("$.place.lng").value(135.5262))
                .andExpect(jsonPath("$.place.durationMin").value(90))
                .andExpect(jsonPath("$.warnings").doesNotExist());
        call(post(items(trip)).contentType(MediaType.APPLICATION_JSON).content("""
                {"placeId":501,"date":"2030-03-16"}"""), trip)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.plannedTime").value(nullValue()))
                .andExpect(jsonPath("$.memo").value(""));

        assertThat(jdbcTemplate.queryForList(
                "select concat(position, '|', ifnull(planned_time, 'NULL'), '|', memo) from itinerary_item "
                        + "where trip_id = uuid_to_bin(?) order by position",
                String.class, trip.tripId()))
                .containsExactly("0|10:30:00|천수각", "1|NULL|");
    }

    @Test
    @DisplayName("없는 장소와 다른 도시 장소는 PLACE_NOT_FOUND, 여행 기간 밖 날짜는 DATE_OUT_OF_TRIP이다")
    void addRejections() throws Exception {
        CreateTripResponse trip = createTrip();
        // 비활성 도시라 다른 통합 테스트의 도시 목록에 영향을 주지 않는다.
        jdbcTemplate.update("""
                insert into city (id, code, name_ko, name_en, country_code, timezone, center_lat, center_lng, active)
                values (9901, 'TST', '테스트', 'Test', 'JP', 'Asia/Tokyo', 35, 139, false)""");
        jdbcTemplate.update("""
                insert into place (id, city_id, name_ko, category, lat, lng, duration_min, opening_hours_json,
                    always_open, source)
                values (9901, 9901, '다른 도시 장소', 'SIGHT', 35, 139, 60, '[]', true, 'SEED')""");
        try {
            expectError(callAdd(trip, 99999L, D1), 404, "PLACE_NOT_FOUND");
            expectError(callAdd(trip, 9901L, D1), 404, "PLACE_NOT_FOUND");
            expectError(callAdd(trip, CASTLE, "2030-03-14"), 400, "DATE_OUT_OF_TRIP");
            expectError(callAdd(trip, CASTLE, "2030-03-19"), 400, "DATE_OUT_OF_TRIP");
            callAdd(trip, CASTLE, D1).andExpect(status().isCreated());
            callAdd(trip, CASTLE, D4).andExpect(status().isCreated());
        } finally {
            jdbcTemplate.update("delete from place where id = 9901");
            jdbcTemplate.update("delete from city where id = 9901");
        }
    }

    @Test
    @DisplayName("PATCH는 전체 교체다: plannedTime을 null이나 생략으로 보내면 지우고 memo는 빈 문자열도 된다")
    void patchReplaces() throws Exception {
        CreateTripResponse trip = createTrip();
        long item = add(trip, CASTLE, D2, "10:00");

        patchItem(trip, item, """
                {"plannedTime":"18:05","memo":"야경"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(item))
                .andExpect(jsonPath("$.date").value(D2))
                .andExpect(jsonPath("$.plannedTime").value("18:05"))
                .andExpect(jsonPath("$.memo").value("야경"))
                .andExpect(jsonPath("$.place.id").value(501));
        patchItem(trip, item, """
                {"plannedTime":null,"memo":""}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedTime").value(nullValue()))
                .andExpect(jsonPath("$.memo").value(""));
        patchItem(trip, item, """
                {"plannedTime":"09:00","memo":"x"}""").andExpect(status().isOk());
        patchItem(trip, item, """
                {"memo":"y"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedTime").value(nullValue()))
                .andExpect(jsonPath("$.memo").value("y"));
        call(get(itinerary(trip)), trip)
                .andExpect(jsonPath("$.days[1].items[0].plannedTime").value(nullValue()))
                .andExpect(jsonPath("$.days[1].items[0].memo").value("y"));
    }

    @Test
    @DisplayName("PATCH에 memo가 없거나 500자를 넘으면 VALIDATION_FAILED이고 500자는 저장된다")
    void memoValidation() throws Exception {
        CreateTripResponse trip = createTrip();
        long item = add(trip, CASTLE, D2, null);
        String memo500 = "가".repeat(500);

        expectError(patchItem(trip, item, """
                {"plannedTime":"10:00"}"""), 400, "VALIDATION_FAILED");
        expectError(patchItem(trip, item, "{\"memo\":\"" + memo500 + "가\"}"), 400, "VALIDATION_FAILED");
        patchItem(trip, item, "{\"memo\":\"" + memo500 + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memo").value(memo500));
        expectError(call(post(items(trip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":501,\"date\":\"2030-03-16\",\"memo\":\"" + memo500 + "가\"}"), trip),
                400, "VALIDATION_FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"9:00", "09:00:00", "24:00", "12:60", "", "0900", "09:00+09:00"})
    @DisplayName("plannedTime은 HH:mm만 허용한다 (POST·PATCH 모두)")
    void strictPlannedTime(String value) throws Exception {
        CreateTripResponse trip = createTrip();
        long item = add(trip, CASTLE, D2, "10:00");

        expectError(call(post(items(trip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":501,\"date\":\"2030-03-16\",\"plannedTime\":\"" + value + "\"}"), trip),
                400, "VALIDATION_FAILED");
        expectError(patchItem(trip, item, "{\"plannedTime\":\"" + value + "\",\"memo\":\"\"}"),
                400, "VALIDATION_FAILED");
    }

    @Test
    @DisplayName("23:59·00:00 경계 시각을 그대로 저장하고 초 없이 돌려준다")
    void plannedTimeRoundTrip() throws Exception {
        CreateTripResponse trip = createTrip();
        add(trip, HOZENJI, D2, "23:59");
        add(trip, HOZENJI, D2, "00:00");

        call(get(itinerary(trip)), trip)
                .andExpect(jsonPath("$.days[1].items[*].plannedTime").value(contains("23:59", "00:00")));
        assertThat(jdbcTemplate.queryForList(
                "select concat(visit_date, ' ', planned_time) from itinerary_item where trip_id = uuid_to_bin(?) "
                        + "order by position",
                String.class, trip.tripId())).containsExactly("2030-03-16 23:59:00", "2030-03-16 00:00:00");
    }

    @Test
    @DisplayName("같은 날짜 순서를 PUT하면 목록 순서대로 0부터 다시 매긴다")
    void reorderSameDate() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        long b = add(trip, HOZENJI, D2, null);
        long c = add(trip, MUSEUM, D2, null);

        order(trip, D2, c, a, b).andExpect(status().isNoContent());

        assertThat(itemIds(trip, 1)).containsExactly(c, a, b);
        assertThat(positions(trip, 1)).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("다른 날짜 항목을 PUT하면 이 날짜로 옮기고 원래 날짜는 0부터 다시 매기며 시각·메모는 유지한다")
    void reorderCrossDateCompactsSource() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        long b = add(trip, HOZENJI, D2, "20:00");
        long c = add(trip, MUSEUM, D2, null);
        long d = add(trip, CASTLE, D3, null);

        order(trip, D3, b, d).andExpect(status().isNoContent());

        assertThat(itemIds(trip, 1)).containsExactly(a, c);
        assertThat(positions(trip, 1)).containsExactly(0, 1);
        assertThat(itemIds(trip, 2)).containsExactly(b, d);
        assertThat(positions(trip, 2)).containsExactly(0, 1);
        call(get(itinerary(trip)), trip).andExpect(jsonPath("$.days[2].items[0].plannedTime").value("20:00"));
    }

    @Test
    @DisplayName("두 날짜에서 동시에 옮겨 오면 두 원래 날짜 모두 다시 매긴다")
    void reorderFromTwoSources() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D1, null);
        long b = add(trip, HOZENJI, D1, null);
        long c = add(trip, MUSEUM, D2, null);
        long d = add(trip, CASTLE, D2, null);

        order(trip, D3, c, a).andExpect(status().isNoContent());

        assertThat(itemIds(trip, 0)).containsExactly(b);
        assertThat(positions(trip, 0)).containsExactly(0);
        assertThat(itemIds(trip, 1)).containsExactly(d);
        assertThat(positions(trip, 1)).containsExactly(0);
        assertThat(itemIds(trip, 2)).containsExactly(c, a);
    }

    @Test
    @DisplayName("이 날짜에 있지만 목록에 없는 항목은 지워지지 않고 기존 순서대로 뒤에 붙는다")
    void unlistedItemsAppended() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        long b = add(trip, HOZENJI, D2, null);
        long c = add(trip, MUSEUM, D2, null);

        order(trip, D2, c).andExpect(status().isNoContent());

        assertThat(itemIds(trip, 1)).containsExactly(c, a, b);
        assertThat(positions(trip, 1)).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("빈 목록 PUT은 허용되고 항목을 지우지 않는다")
    void emptyOrder() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        long b = add(trip, HOZENJI, D2, null);

        order(trip, D2).andExpect(status().isNoContent());
        order(trip, D3).andExpect(status().isNoContent());

        assertThat(itemIds(trip, 1)).containsExactly(a, b);
    }

    @Test
    @DisplayName("다른 여행의 항목이나 없는 항목이 섞인 PUT은 ITINERARY_ITEM_NOT_FOUND이고 아무것도 바뀌지 않는다")
    void reorderOtherTripItem() throws Exception {
        CreateTripResponse trip = createTrip();
        CreateTripResponse other = createTrip();
        long a = add(trip, CASTLE, D2, null);
        long b = add(trip, HOZENJI, D2, null);
        long foreign = add(other, CASTLE, D2, null);

        expectError(order(trip, D2, b, foreign, a), 404, "ITINERARY_ITEM_NOT_FOUND");
        expectError(order(trip, D3, a, 99999999L), 404, "ITINERARY_ITEM_NOT_FOUND");

        assertThat(itemIds(trip, 1)).containsExactly(a, b);
        assertThat(itemIds(other, 1)).containsExactly(foreign);
    }

    @Test
    @DisplayName("다른 여행의 항목은 PATCH·DELETE 모두 ITINERARY_ITEM_NOT_FOUND이고 없는 항목도 같다")
    void otherTripItemOnItemEndpoints() throws Exception {
        CreateTripResponse trip = createTrip();
        CreateTripResponse other = createTrip();
        long foreign = add(other, CASTLE, D2, "10:00");

        expectError(patchItem(trip, foreign, """
                {"memo":"탈취"}"""), 404, "ITINERARY_ITEM_NOT_FOUND");
        expectError(call(delete(items(trip) + "/" + foreign), trip), 404, "ITINERARY_ITEM_NOT_FOUND");
        expectError(call(delete(items(trip) + "/99999999"), trip), 404, "ITINERARY_ITEM_NOT_FOUND");
        call(get(itinerary(other)), other)
                .andExpect(jsonPath("$.days[1].items[0].plannedTime").value("10:00"))
                .andExpect(jsonPath("$.days[1].items[0].memo").value(""));
    }

    @Test
    @DisplayName("PUT의 중복 ID, 200개 초과, null 항목, 누락된 itemIds, 잘못된 경로 날짜는 VALIDATION_FAILED이고 기간 밖 날짜는 DATE_OUT_OF_TRIP이다")
    void orderValidation() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        String tooMany = IntStream.rangeClosed(1, 201).mapToObj(String::valueOf).collect(Collectors.joining(","));

        expectError(order(trip, D2, a, a), 400, "VALIDATION_FAILED");
        expectError(rawOrder(trip, D2, "{\"itemIds\":[" + tooMany + "]}"), 400, "VALIDATION_FAILED");
        expectError(rawOrder(trip, D2, "{\"itemIds\":[null]}"), 400, "VALIDATION_FAILED");
        expectError(rawOrder(trip, D2, "{}"), 400, "VALIDATION_FAILED");
        expectError(rawOrder(trip, "2030-13-01", "{\"itemIds\":[]}"), 400, "VALIDATION_FAILED");
        expectError(rawOrder(trip, "not-a-date", "{\"itemIds\":[]}"), 400, "VALIDATION_FAILED");
        expectError(rawOrder(trip, "2030-03-14", "{\"itemIds\":[]}"), 400, "DATE_OUT_OF_TRIP");
        expectError(rawOrder(trip, "2030-03-19", "{\"itemIds\":[]}"), 400, "DATE_OUT_OF_TRIP");
        assertThat(itemIds(trip, 1)).containsExactly(a);
    }

    @Test
    @DisplayName("200개 PUT은 허용 범위라 ID 존재 여부 판정까지 간다")
    void order200IdsPassesValidation() throws Exception {
        CreateTripResponse trip = createTrip();
        String ids = IntStream.rangeClosed(1, 200).map(i -> 90_000_000 + i).mapToObj(String::valueOf)
                .collect(Collectors.joining(","));

        expectError(rawOrder(trip, D2, "{\"itemIds\":[" + ids + "]}"), 404, "ITINERARY_ITEM_NOT_FOUND");
    }

    @Test
    @DisplayName("날짜의 항목이 200개면 POST는 ITINERARY_DAY_FULL이고 199개면 200번째까지 들어가며 하나 지우면 다시 넣을 수 있다")
    void postDayCap() throws Exception {
        CreateTripResponse trip = createTrip();
        seedItems(trip, D2, 199);

        long last = add(trip, HOZENJI, D2, null);
        assertThat(itemCount(trip, D2)).isEqualTo(200);
        expectError(callAdd(trip, HOZENJI, D2), 400, "ITINERARY_DAY_FULL");
        assertThat(itemCount(trip, D2)).isEqualTo(200);

        callAdd(trip, HOZENJI, D3).andExpect(status().isCreated());
        call(delete(items(trip) + "/" + last), trip).andExpect(status().isNoContent());
        callAdd(trip, HOZENJI, D2).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("옮겨 온 결과가 200개를 넘는 PUT은 ITINERARY_DAY_FULL이고 아무것도 바뀌지 않으며 정확히 200개면 허용한다")
    void putDayCap() throws Exception {
        CreateTripResponse trip = createTrip();
        seedItems(trip, D2, 199);
        long a = add(trip, CASTLE, D3, null);
        long b = add(trip, HOZENJI, D3, null);
        long c = add(trip, MUSEUM, D3, null);

        order(trip, D2, a).andExpect(status().isNoContent());
        assertThat(itemCount(trip, D2)).isEqualTo(200);
        assertThat(itemIds(trip, 2)).containsExactly(b, c);
        assertThat(positions(trip, 2)).containsExactly(0, 1);

        expectError(order(trip, D2, b), 400, "ITINERARY_DAY_FULL");
        assertThat(itemCount(trip, D2)).isEqualTo(200);
        assertThat(itemIds(trip, 2)).containsExactly(b, c);
        assertThat(positions(trip, 2)).containsExactly(0, 1);

        order(trip, D2).andExpect(status().isNoContent());
        order(trip, D3, c, b).andExpect(status().isNoContent());
        assertThat(itemIds(trip, 2)).containsExactly(c, b);
    }

    @Test
    @DisplayName("DELETE는 204이고 그 날짜를 0부터 다시 매기며 다른 날짜는 건드리지 않는다")
    void deleteCompacts() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        long b = add(trip, HOZENJI, D2, null);
        long c = add(trip, MUSEUM, D2, null);
        long d = add(trip, CASTLE, D3, null);

        call(delete(items(trip) + "/" + a), trip).andExpect(status().isNoContent());

        assertThat(itemIds(trip, 1)).containsExactly(b, c);
        assertThat(positions(trip, 1)).containsExactly(0, 1);
        assertThat(itemIds(trip, 2)).containsExactly(d);
        long next = add(trip, CASTLE, D2, null);
        assertThat(itemIds(trip, 1)).containsExactly(b, c, next);
        assertThat(positions(trip, 1)).containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("없거나 형식이 틀린 여행은 토큰이 없어도 모든 일정 API에서 404가 403보다 먼저다")
    void tripNotFoundBeforeForbidden() throws Exception {
        for (String tripId : List.of(MISSING_TRIP, "not-a-uuid")) {
            String base = "/api/v1/trips/" + tripId + "/itinerary";
            expectError(mockMvc.perform(get(base)), 404, "TRIP_NOT_FOUND");
            expectError(mockMvc.perform(post(base + "/items").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"placeId\":501,\"date\":\"2030-03-16\"}")), 404, "TRIP_NOT_FOUND");
            expectError(mockMvc.perform(patch(base + "/items/1").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"memo\":\"\"}")), 404, "TRIP_NOT_FOUND");
            expectError(mockMvc.perform(put(base + "/days/2030-03-16/order").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"itemIds\":[]}")), 404, "TRIP_NOT_FOUND");
            expectError(mockMvc.perform(delete(base + "/items/1")), 404, "TRIP_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("토큰이 없거나 다르면 모든 일정 API가 403이고 항목이 바뀌지 않는다")
    void forbidden() throws Exception {
        CreateTripResponse trip = createTrip();
        long item = add(trip, CASTLE, D2, "10:00");

        for (String token : new String[] {null, "wrong-token"}) {
            expectError(withToken(get(itinerary(trip)), token), 403, "FORBIDDEN_TRIP");
            expectError(withToken(post(items(trip)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"placeId\":501,\"date\":\"2030-03-16\"}"), token), 403, "FORBIDDEN_TRIP");
            expectError(withToken(patch(items(trip) + "/" + item).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"memo\":\"탈취\"}"), token), 403, "FORBIDDEN_TRIP");
            expectError(withToken(put(itinerary(trip) + "/days/" + D3 + "/order")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"itemIds\":[" + item + "]}"), token),
                    403, "FORBIDDEN_TRIP");
            expectError(withToken(delete(items(trip) + "/" + item), token), 403, "FORBIDDEN_TRIP");
        }
        call(get(itinerary(trip)), trip)
                .andExpect(jsonPath("$.days[1].items[0].itemId").value(item))
                .andExpect(jsonPath("$.days[1].items[0].memo").value(""));
    }

    @Test
    @DisplayName("항목이 있는 여행을 삭제하면 항목도 함께 삭제된다")
    void deleteTripWithItems() throws Exception {
        CreateTripResponse trip = createTrip();
        add(trip, CASTLE, D2, null);
        add(trip, HOZENJI, D3, "10:00");

        mockMvc.perform(delete("/api/v1/trips/" + trip.tripId()).header(TripController.OWNER_TOKEN_HEADER,
                trip.ownerToken())).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from itinerary_item where trip_id = uuid_to_bin(?)", Integer.class, trip.tripId()))
                .isZero();
        expectError(call(get(itinerary(trip)), trip), 404, "TRIP_NOT_FOUND");
    }

    @Test
    @DisplayName("같은 날짜에 동시에 추가해도 잠금으로 직렬화되어 위치가 겹치지 않는다")
    void concurrentAddsSerialize() throws Exception {
        CreateTripResponse trip = createTrip();
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> itineraryService.addItem(trip.tripId(), trip.ownerToken(),
                        new AddItineraryItemRequest(CASTLE, LocalDate.parse(D2), null, null))));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(positions(trip, 1)).containsExactly(0, 1, 2, 3, 4, 5);
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠금을 쥔 동안 PUT은 기다렸다가 커밋된 최신 목록으로 계산한다")
    void writerWaitsForLockAndReadsCommittedItems() throws Exception {
        CreateTripResponse trip = createTrip();
        long a = add(trip, CASTLE, D2, null);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?>[] waiter = new Future<?>[1];
            long[] added = new long[1];
            transactionTemplate.executeWithoutResult(tx -> {
                ItineraryItemResponse item = itineraryService.addItem(trip.tripId(), trip.ownerToken(),
                        new AddItineraryItemRequest(HOZENJI, LocalDate.parse(D2), null, null));
                added[0] = item.itemId();
                // 잠금을 쥔 채 커밋 전에 PUT을 시작시킨다. 스냅샷이 잠금 전에 잡히면 새 항목을 못 보고 위치가 겹친다.
                waiter[0] = executor.submit(() -> itineraryService.reorder(trip.tripId(), trip.ownerToken(),
                        LocalDate.parse(D2), List.of(a)));
                sleepQuietly(300);
                assertThat(waiter[0].isDone()).isFalse();
            });
            waiter[0].get();

            assertThat(itemIds(trip, 1)).containsExactly(a, added[0]);
            assertThat(positions(trip, 1)).containsExactly(0, 1);
        } finally {
            executor.shutdownNow();
        }
    }

    /** 상한 테스트 준비용으로 POST 없이 position 0부터 count개를 넣는다. */
    private void seedItems(CreateTripResponse trip, String date, int count) {
        List<Object[]> rows = IntStream.range(0, count)
                .mapToObj(position -> new Object[] {trip.tripId(), CASTLE, date, position})
                .toList();
        jdbcTemplate.batchUpdate("""
                insert into itinerary_item (trip_id, place_id, visit_date, position, memo, created_at, updated_at)
                values (uuid_to_bin(?), ?, ?, ?, '', utc_timestamp(6), utc_timestamp(6))""", rows);
    }

    private int itemCount(CreateTripResponse trip, String date) {
        return jdbcTemplate.queryForObject(
                "select count(*) from itinerary_item where trip_id = uuid_to_bin(?) and visit_date = ?",
                Integer.class, trip.tripId(), date);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private CreateTripResponse createTrip() {
        return createTrip("2030-03-15T14:00:00+09:00", "2030-03-18T18:00:00+09:00");
    }

    private CreateTripResponse createTrip(String arrivalAt, String returnDepartureAt) {
        return tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", NAMBA, START, MONDAY,
                new ManualOverride(time(arrivalAt), "KIX", time(returnDepartureAt), "KIX")));
    }

    private static OffsetDateTime time(String iso) {
        return OffsetDateTime.parse(iso);
    }

    private static String itinerary(CreateTripResponse trip) {
        return "/api/v1/trips/" + trip.tripId() + "/itinerary";
    }

    private static String items(CreateTripResponse trip) {
        return itinerary(trip) + "/items";
    }

    private ResultActions call(MockHttpServletRequestBuilder request, CreateTripResponse trip) throws Exception {
        return mockMvc.perform(request.header(TripController.OWNER_TOKEN_HEADER, trip.ownerToken()));
    }

    private ResultActions withToken(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(token == null ? request : request.header(TripController.OWNER_TOKEN_HEADER, token));
    }

    private ResultActions callAdd(CreateTripResponse trip, long placeId, String date) throws Exception {
        return call(post(items(trip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":" + placeId + ",\"date\":\"" + date + "\"}"), trip);
    }

    private long add(CreateTripResponse trip, long placeId, String date, String plannedTime) throws Exception {
        String time = plannedTime == null ? "null" : "\"" + plannedTime + "\"";
        String body = call(post(items(trip)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"placeId\":" + placeId + ",\"date\":\"" + date + "\",\"plannedTime\":" + time + "}"), trip)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<Number>read(body, "$.itemId").longValue();
    }

    private ResultActions patchItem(CreateTripResponse trip, long itemId, String body) throws Exception {
        return call(patch(items(trip) + "/" + itemId).contentType(MediaType.APPLICATION_JSON).content(body), trip);
    }

    private ResultActions order(CreateTripResponse trip, String date, long... itemIds) throws Exception {
        String ids = Arrays.stream(itemIds).mapToObj(String::valueOf).collect(Collectors.joining(","));
        return rawOrder(trip, date, "{\"itemIds\":[" + ids + "]}");
    }

    private ResultActions rawOrder(CreateTripResponse trip, String date, String body) throws Exception {
        return call(put(itinerary(trip) + "/days/" + date + "/order").contentType(MediaType.APPLICATION_JSON)
                .content(body), trip);
    }

    private List<Long> itemIds(CreateTripResponse trip, int dayIndex) throws Exception {
        List<Number> ids = JsonPath.read(itineraryJson(trip), "$.days[" + dayIndex + "].items[*].itemId");
        return ids.stream().map(Number::longValue).toList();
    }

    private List<Integer> positions(CreateTripResponse trip, int dayIndex) throws Exception {
        return JsonPath.read(itineraryJson(trip), "$.days[" + dayIndex + "].items[*].position");
    }

    private String itineraryJson(CreateTripResponse trip) throws Exception {
        return call(get(itinerary(trip)), trip)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static void expectError(ResultActions actions, int status, String code) throws Exception {
        actions.andExpect(status().is(status)).andExpect(jsonPath("$.code").value(code));
    }
}
