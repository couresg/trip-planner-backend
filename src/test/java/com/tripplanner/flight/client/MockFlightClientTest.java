package com.tripplanner.flight.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MockFlightClientTest {

    private final MockFlightClient client = new MockFlightClient(new AeroDataBoxParser(JsonMapper.builder().build()));

    @Test
    @DisplayName("fixture 날짜와 같은 날 조회하면 fixture 시각 그대로 반환한다")
    void sameDate() {
        List<FlightLeg> legs = client.findLegs(FlightNumber.of("KE723"), LocalDate.of(2026, 11, 5));

        FlightLeg leg = legs.getFirst();
        assertThat(leg.departure().iata()).isEqualTo("ICN");
        assertThat(leg.departure().scheduledLocal()).isEqualTo(OffsetDateTime.parse("2026-11-05T12:00:00+09:00"));
        assertThat(leg.arrival().scheduledLocal()).isEqualTo(OffsetDateTime.parse("2026-11-05T13:45:00+09:00"));
        assertThat(leg.arrival().revisedLocal()).isEqualTo(OffsetDateTime.parse("2026-11-05T13:52:00+09:00"));
    }

    @Test
    @DisplayName("다른 날짜로 조회하면 날짜 차이만큼 모든 시각(revised 포함)을 옮긴다")
    void rebaseToRequestedDate() {
        FlightLeg leg = client.findLegs(FlightNumber.of("OZ112"), LocalDate.of(2027, 1, 20)).getFirst();

        assertThat(leg.departure().scheduledLocal()).isEqualTo(OffsetDateTime.parse("2027-01-20T09:00:00+09:00"));
        assertThat(leg.departure().revisedLocal()).isEqualTo(OffsetDateTime.parse("2027-01-20T10:30:00+09:00"));
        assertThat(leg.arrival().revisedLocal()).isEqualTo(OffsetDateTime.parse("2027-01-20T12:20:00+09:00"));
        assertThat(leg.status()).isEqualTo("Delayed");
    }

    @Test
    @DisplayName("자정을 넘겨 도착하는 편은 옮긴 뒤에도 다음 날 00:20 도착이다")
    void midnightCrossing() {
        FlightLeg leg = client.findLegs(FlightNumber.of("7C1302"), LocalDate.of(2026, 12, 31)).getFirst();

        assertThat(leg.departure().scheduledLocal()).isEqualTo(OffsetDateTime.parse("2026-12-31T22:30:00+09:00"));
        assertThat(leg.arrival().scheduledLocal()).isEqualTo(OffsetDateTime.parse("2027-01-01T00:20:00+09:00"));
    }

    @Test
    @DisplayName("UTC로는 전날 출발이어도 출발지 현지 날짜 기준으로 옮긴다")
    void rebaseByLocalDate() {
        FlightLeg leg = client.findLegs(FlightNumber.of("LJ231"), LocalDate.of(2026, 11, 10)).getFirst();

        assertThat(leg.departure().scheduledLocal()).isEqualTo(OffsetDateTime.parse("2026-11-10T07:00:00+09:00"));
        assertThat(leg.status()).isEqualTo("Canceled");
    }

    @Test
    @DisplayName("다구간 편명은 모든 구간을 반환하고 가장 이른 출발 구간 기준으로 날짜를 맞춘다")
    void multiLeg() {
        List<FlightLeg> legs = client.findLegs(FlightNumber.of("TW9901"), LocalDate.of(2026, 11, 6));

        assertThat(legs).hasSize(2);
        assertThat(legs).extracting(leg -> leg.departure().iata() + "-" + leg.arrival().iata())
                .containsExactlyInAnyOrder("ICN-FUK", "FUK-KIX");
        assertThat(legs).extracting(leg -> leg.departure().scheduledLocal().toLocalDate())
                .containsOnly(LocalDate.of(2026, 11, 6));
    }

    @Test
    @DisplayName("fixture가 없는 편명은 빈 목록이다")
    void unknownFlight() {
        assertThat(client.findLegs(FlightNumber.of("ZZ9999"), LocalDate.of(2026, 11, 5))).isEmpty();
    }
}
