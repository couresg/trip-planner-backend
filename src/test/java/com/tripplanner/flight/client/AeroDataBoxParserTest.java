package com.tripplanner.flight.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightPoint;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AeroDataBoxParserTest {

    private final AeroDataBoxParser parser = new AeroDataBoxParser(JsonMapper.builder().build());

    @Test
    @DisplayName("출발·도착 공항, UTC 시각, 공항 timezone, revised, 터미널, 상태를 읽는다")
    void parseFull() {
        List<FlightLeg> legs = parser.parse("""
                [{
                  "number": "KE 723", "status": "Expected",
                  "departure": {
                    "airport": {"iata": "ICN", "timeZone": "Asia/Seoul"},
                    "scheduledTime": {"utc": "2026-11-05 03:00Z", "local": "2026-11-05 12:00+09:00"},
                    "terminal": "2"
                  },
                  "arrival": {
                    "airport": {"iata": "KIX", "timeZone": "Asia/Tokyo"},
                    "scheduledTime": {"utc": "2026-11-05 04:45Z", "local": "2026-11-05 13:45+09:00"},
                    "revisedTime": {"utc": "2026-11-05 04:52Z", "local": "2026-11-05 13:52+09:00"},
                    "terminal": "1"
                  }
                }]
                """);

        assertThat(legs).containsExactly(new FlightLeg(
                new FlightPoint("ICN", ZoneId.of("Asia/Seoul"), Instant.parse("2026-11-05T03:00:00Z"), null, "2"),
                new FlightPoint("KIX", ZoneId.of("Asia/Tokyo"), Instant.parse("2026-11-05T04:45:00Z"),
                        Instant.parse("2026-11-05T04:52:00Z"), "1"),
                "Expected"));
    }

    @Test
    @DisplayName("선택 필드가 없으면 utc 대신 local 시각, timezone 대신 현지 오프셋, 상태 Unknown을 쓴다")
    void parseMissingOptionalFields() {
        List<FlightLeg> legs = parser.parse("""
                [{
                  "departure": {"airport": {"iata": "ICN"}, "scheduledTime": {"local": "2026-11-05 12:00+09:00"}},
                  "arrival": {"airport": {"iata": "KIX", "timeZone": "Not/AZone"},
                              "scheduledTime": {"utc": "2026-11-05 04:45Z"}, "revisedTime": {}}
                }]
                """);

        FlightLeg leg = legs.getFirst();
        assertThat(leg.status()).isEqualTo("Unknown");
        assertThat(leg.departure().scheduled()).isEqualTo(Instant.parse("2026-11-05T03:00:00Z"));
        assertThat(leg.departure().timezone()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(leg.departure().terminal()).isNull();
        assertThat(leg.arrival().timezone()).isEqualTo(ZoneOffset.UTC);
        assertThat(leg.arrival().revised()).isNull();
    }

    @Test
    @DisplayName("공항 IATA나 예정 시각이 없는 구간은 건너뛴다")
    void skipIncompleteLegs() {
        List<FlightLeg> legs = parser.parse("""
                [
                  {"departure": {"airport": {}, "scheduledTime": {"utc": "2026-11-05 03:00Z"}},
                   "arrival": {"airport": {"iata": "KIX"}, "scheduledTime": {"utc": "2026-11-05 04:45Z"}}},
                  {"departure": {"airport": {"iata": "ICN"}, "scheduledTime": {"utc": "not-a-time"}},
                   "arrival": {"airport": {"iata": "KIX"}, "scheduledTime": {"utc": "2026-11-05 04:45Z"}}}
                ]
                """);

        assertThat(legs).isEmpty();
    }

    @Test
    @DisplayName("빈 배열은 빈 목록이다")
    void emptyArray() {
        assertThat(parser.parse("[]")).isEmpty();
    }

    @Test
    @DisplayName("JSON이 아니거나 배열이 아니면 공급자 장애로 본다")
    void malformed() {
        assertThatThrownBy(() -> parser.parse("<html>"))
                .isInstanceOf(FlightProviderException.class)
                .extracting(e -> ((FlightProviderException) e).errorCode())
                .isEqualTo(ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE);
        assertThatThrownBy(() -> parser.parse("{\"message\":\"quota\"}")).isInstanceOf(FlightProviderException.class);
    }
}
