package com.tripplanner.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.flight.client.FlightClient;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.domain.FlightPoint;
import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 교통편·일정 GET의 항공편 재조회를 실제 MySQL(Testcontainers)·FlightService 캐시와 함께 검증한다.
 * 공급자(FlightClient)는 목, 시각은 바꿀 수 있는 Clock. 공급자 장애 후 30초 대기가 다른 테스트를 막지 않도록
 * 장애 테스트가 더 이른 날짜(2031-04)를 쓴다.
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class FlightRefreshIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2031-01-01T00:00:00Z"));

    @TestBean(name = "clock")
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @MockitoBean
    FlightClient flightClient;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TripService tripService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("생성 뒤 지연이 생기면 23:30 도착이 다음 날 00:40으로 다시 계산되고, 지연 축소·결항도 반영된다")
    void delayIsAppliedOnArrivalTab() throws Exception {
        LocalDate start = LocalDate.of(2031, 5, 9);
        ZonedDateTime departure = ZonedDateTime.of(2031, 5, 9, 21, 20, 0, 0, SEOUL);
        ZonedDateTime arrival = ZonedDateTime.of(2031, 5, 9, 23, 30, 0, 0, TOKYO);
        CLOCK.set(Instant.parse("2031-05-01T00:00:00Z"));
        stubOutbound(start, departure, arrival, null, "Expected");
        stubReturn(start.plusDays(3), null);
        CreateTripResponse trip = create(start);

        arrival(trip)
                .andExpect(jsonPath("$.baseTime").value("2031-05-09T23:30:00+09:00"))
                .andExpect(jsonPath("$.flightCheck.result").value("OUT_OF_WINDOW"))
                .andExpect(jsonPath("$.flightCheck.checkedAt").value("2031-05-01T09:00:00+09:00"));
        verify(flightClient, times(1)).findLegs(FlightNumber.of("KE723"), start);

        CLOCK.set(departure.minusHours(2).toInstant());
        stubOutbound(start, departure, arrival, arrival.plusMinutes(70), "Delayed");
        arrival(trip)
                .andExpect(jsonPath("$.scheduledArrivalAt").value("2031-05-09T23:30:00+09:00"))
                .andExpect(jsonPath("$.baseTime").value("2031-05-10T00:40:00+09:00"))
                .andExpect(jsonPath("$.windowEnd").value("2031-05-10T02:40:00+09:00"))
                .andExpect(jsonPath("$.flightStatus").value("Delayed"))
                .andExpect(jsonPath("$.flightCheck.result").value("REFRESHED"))
                .andExpect(jsonPath("$.flightCheck.checkedAt").value("2031-05-09T19:20:00+09:00"));
        assertThat(storedRevisedArrival(trip)).isEqualTo(arrival.plusMinutes(70).toInstant());

        CLOCK.advance(Duration.ofMinutes(11));
        stubOutbound(start, departure, arrival, arrival.plusMinutes(20), "Delayed");
        arrival(trip).andExpect(jsonPath("$.baseTime").value("2031-05-09T23:50:00+09:00"));

        CLOCK.advance(Duration.ofMinutes(11));
        stubOutbound(start, departure, arrival, null, "Canceled");
        arrival(trip)
                .andExpect(jsonPath("$.baseTime").value("2031-05-09T23:30:00+09:00"))
                .andExpect(jsonPath("$.flightStatus").value("Canceled"));
        assertThat(storedRevisedArrival(trip)).isNull();
    }

    @Test
    @DisplayName("공급자 장애면 귀국일·일정은 저장된 값으로 200 응답하고 귀국일은 FAILED와 마지막 확인 시각을 준다")
    void providerFailureFallsBackToStoredValues() throws Exception {
        LocalDate start = LocalDate.of(2031, 4, 4);
        LocalDate end = start.plusDays(3);
        CLOCK.set(Instant.parse("2031-03-20T00:00:00Z"));
        stubOutbound(start, ZonedDateTime.of(2031, 4, 4, 12, 0, 0, 0, SEOUL),
                ZonedDateTime.of(2031, 4, 4, 14, 10, 0, 0, TOKYO), null, "Expected");
        stubReturn(end, null);
        CreateTripResponse trip = create(start);

        // 오는 편 출발 3시간 전: 가는 편은 구간 밖, 오는 편만 조회한다. 만료된 DB 캐시 대체가 없도록 지운다.
        CLOCK.set(ZonedDateTime.of(2031, 4, 7, 15, 0, 0, 0, TOKYO).toInstant());
        jdbcTemplate.update("DELETE FROM flight_cache WHERE flight_no = ? AND flight_date = ?", "KE724", end);
        willThrow(new FlightProviderException("timeout", null)).given(flightClient)
                .findLegs(FlightNumber.of("KE724"), end);

        call("/transfers/departure", trip)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightDepartureAt").value("2031-04-07T18:00:00+09:00"))
                .andExpect(jsonPath("$.flightStatus").value("Expected"))
                .andExpect(jsonPath("$.flightCheck.result").value("FAILED"))
                .andExpect(jsonPath("$.flightCheck.checkedAt").value("2031-03-20T09:00:00+09:00"));
        call("/itinerary", trip).andExpect(status().isOk());
    }

    private CreateTripResponse create(LocalDate start) {
        return tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", 11L, start, start.plusDays(3),
                null));
    }

    private void stubOutbound(LocalDate date, ZonedDateTime departure, ZonedDateTime arrival,
            ZonedDateTime revisedArrival, String status) {
        given(flightClient.findLegs(FlightNumber.of("KE723"), date)).willReturn(List.of(new FlightLeg(
                new FlightPoint("ICN", SEOUL, departure.toInstant(), null, "2"),
                new FlightPoint("KIX", TOKYO, arrival.toInstant(),
                        revisedArrival == null ? null : revisedArrival.toInstant(), "1"),
                status)));
    }

    private void stubReturn(LocalDate date, ZonedDateTime revisedDeparture) {
        ZonedDateTime departure = date.atTime(18, 0).atZone(TOKYO);
        given(flightClient.findLegs(FlightNumber.of("KE724"), date)).willReturn(List.of(new FlightLeg(
                new FlightPoint("KIX", TOKYO, departure.toInstant(),
                        revisedDeparture == null ? null : revisedDeparture.toInstant(), "1"),
                new FlightPoint("ICN", SEOUL, departure.plusHours(2).toInstant(), null, "2"),
                "Expected")));
    }

    private ResultActions arrival(CreateTripResponse trip) throws Exception {
        return call("/transfers/arrival", trip).andExpect(status().isOk());
    }

    private ResultActions call(String path, CreateTripResponse trip) throws Exception {
        return mockMvc.perform(get("/api/v1/trips/" + trip.tripId() + path)
                .header("X-Owner-Token", trip.ownerToken()));
    }

    /** DATETIME(6)은 UTC 벽시계 값으로 저장된다 (hibernate.jdbc.time_zone). JVM 시간대 변환을 피하려고 문자열로 읽는다. */
    private Instant storedRevisedArrival(CreateTripResponse trip) {
        String value = jdbcTemplate.queryForObject("SELECT DATE_FORMAT(revised_arr_utc, '%Y-%m-%dT%H:%i:%sZ') "
                + "FROM trip_flight WHERE trip_id = UUID_TO_BIN(?) AND direction = 'OUTBOUND'", String.class,
                trip.tripId());
        return value == null ? null : Instant.parse(value);
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
