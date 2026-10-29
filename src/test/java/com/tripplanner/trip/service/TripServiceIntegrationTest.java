package com.tripplanner.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.api.ManualOverride;
import com.tripplanner.trip.api.TripDetailResponse;
import com.tripplanner.trip.api.TripSummaryResponse;
import com.tripplanner.trip.domain.FlightSource;
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

/** test 프로필(external.mock=true) + Testcontainers MySQL로 V2 마이그레이션과 trip·trip_flight 저장·조회·삭제를 검증한다. */
@Tag("integration")
@SpringBootTest
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class TripServiceIntegrationTest {

    private static final LocalDate START = LocalDate.of(2030, 3, 10);
    private static final LocalDate END = LocalDate.of(2030, 3, 13);

    @Autowired
    TripService tripService;

    @Autowired
    OwnerTokenService ownerTokenService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("V2 마이그레이션으로 trip_flight의 출발·도착 공항과 예정 시각 컬럼이 NULL 허용이 된다")
    void v2MakesFlightPointsNullable() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
                select column_name as name, is_nullable as nullable from information_schema.columns
                where table_schema = database() and table_name = 'trip_flight'
                  and column_name in ('dep_airport_iata', 'scheduled_dep_utc', 'arr_airport_iata', 'scheduled_arr_utc')
                """);
        Integer v2Applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '2' and success = true", Integer.class);

        assertThat(columns).hasSize(4).allSatisfy(c -> assertThat(c.get("nullable")).isEqualTo("YES"));
        assertThat(v2Applied).isOne();
    }

    @Test
    @DisplayName("항공편 조회로 만든 여행은 trip 1행과 trip_flight 2행으로 저장되고 토큰은 해시만 저장된다")
    void createWithLookupStoresRows() {
        CreateTripResponse created = tripService.create(request("KE723", "KE724", START, null));

        Map<String, Object> trip = jdbcTemplate.queryForMap(
                "select owner_token_hash, city_id, area_id, start_date, end_date from trip where id = uuid_to_bin(?)",
                created.tripId());
        List<Map<String, Object>> flights = jdbcTemplate.queryForList("""
                select direction, flight_no, dep_airport_iata, arr_airport_iata, source, status
                from trip_flight where trip_id = uuid_to_bin(?) order by direction
                """, created.tripId());

        assertThat(trip.get("owner_token_hash")).isEqualTo(ownerTokenService.hash(created.ownerToken()));
        assertThat(trip.get("area_id")).isEqualTo(11L);
        assertThat(flights).extracting(f -> f.get("direction") + ":" + f.get("flight_no") + ":"
                        + f.get("dep_airport_iata") + "-" + f.get("arr_airport_iata") + ":" + f.get("source"))
                .containsExactly("OUTBOUND:KE723:ICN-KIX:AERODATABOX", "RETURN:KE724:KIX-ICN:AERODATABOX");
        Integer tokenStored = jdbcTemplate.queryForObject(
                "select count(*) from trip where owner_token_hash = ?", Integer.class, created.ownerToken());
        assertThat(tokenStored).isZero();
    }

    @Test
    @DisplayName("시작일 22:30 출발 7C1302는 다음 날 00:20(+09:00) 도착으로 상세에 표시된다")
    void overnightArrivalDetail() {
        CreateTripResponse created = tripService.create(request("7C1302", "KE724", START, null));

        TripDetailResponse detail = tripService.get(created.tripId(), created.ownerToken());

        assertThat(detail.outbound().arrivalAt()).isEqualTo(OffsetDateTime.parse("2030-03-11T00:20:00+09:00"));
        assertThat(detail.outbound().arrivalAt().getOffset().getId()).isEqualTo("+09:00");
        assertThat(detail.returnFlight().departureAt()).isEqualTo(OffsetDateTime.parse("2030-03-13T15:00:00+09:00"));
        assertThat(detail.area().nameKo()).isEqualTo("난바");
        assertThat(detail.city().timezone()).isEqualTo("Asia/Tokyo");
    }

    @Test
    @DisplayName("수동 가는 편은 출발 공항·시각이 NULL인 행으로 저장되고 상세에서 MANUAL로 조회된다")
    void manualOutboundWithNullDeparture() {
        ManualOverride manual = new ManualOverride(OffsetDateTime.parse("2030-03-10T13:45:00+09:00"), "KIX", null,
                null);

        CreateTripResponse created = tripService.create(request("ZZ9999", "KE724", START, manual));

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select dep_airport_iata, scheduled_dep_utc, arr_airport_iata, scheduled_arr_utc, status, source
                from trip_flight where trip_id = uuid_to_bin(?) and direction = 'OUTBOUND'
                """, created.tripId());
        assertThat(row.get("dep_airport_iata")).isNull();
        assertThat(row.get("scheduled_dep_utc")).isNull();
        assertThat(row.get("arr_airport_iata")).isEqualTo("KIX");
        assertThat(row.get("scheduled_arr_utc")).isNotNull();
        assertThat(row.get("status")).isEqualTo("Manual");
        assertThat(row.get("source")).isEqualTo("MANUAL");

        TripDetailResponse detail = tripService.get(created.tripId(), created.ownerToken());
        assertThat(detail.outbound()).isEqualTo(new TripDetailResponse.Outbound("ZZ9999", "KIX",
                OffsetDateTime.parse("2030-03-10T13:45:00+09:00"), FlightSource.MANUAL, "Manual"));
    }

    @Test
    @DisplayName("목록은 여러 토큰의 여행을 시작일 순으로 반환하고 모르는 토큰·빈 항목은 무시한다")
    void listByMultipleTokens() {
        CreateTripResponse later = tripService.create(request("KE723", "KE724", START.plusDays(20), null));
        CreateTripResponse earlier = tripService.create(request("KE723", "KE724", START.plusDays(5), null));
        CreateTripResponse notMine = tripService.create(request("KE723", "KE724", START.plusDays(1), null));

        List<TripSummaryResponse> trips = tripService.list(
                later.ownerToken() + ", ," + earlier.ownerToken() + "," + ownerTokenService.issue());

        assertThat(trips).extracting(TripSummaryResponse::tripId).containsExactly(earlier.tripId(), later.tripId())
                .doesNotContain(notMine.tripId());
        assertThat(trips.getFirst()).isEqualTo(new TripSummaryResponse(earlier.tripId(),
                new TripSummaryResponse.CityRef("OSA", "오사카"), new TripSummaryResponse.AreaRef(11L, "난바"),
                START.plusDays(5), START.plusDays(8), "KE723", "KE724"));
    }

    @Test
    @DisplayName("삭제하면 trip_flight도 함께 지워지고, 이후 조회는 TRIP_NOT_FOUND")
    void deleteCascades() {
        CreateTripResponse created = tripService.create(request("KE723", "KE724", START, null));
        assertThat(countFlights(created.tripId())).isEqualTo(2);

        assertThatThrownBy(() -> tripService.delete(created.tripId(), ownerTokenService.issue()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN_TRIP));
        tripService.delete(created.tripId(), created.ownerToken());

        assertThat(countFlights(created.tripId())).isZero();
        assertThatThrownBy(() -> tripService.get(created.tripId(), created.ownerToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.TRIP_NOT_FOUND));
    }

    private int countFlights(String tripId) {
        return jdbcTemplate.queryForObject("select count(*) from trip_flight where trip_id = uuid_to_bin(?)",
                Integer.class, tripId);
    }

    private static CreateTripRequest request(String outbound, String inbound, LocalDate start,
            ManualOverride manual) {
        return new CreateTripRequest(outbound, inbound, "OSA", 11L, start, start.plusDays(3), manual);
    }
}
