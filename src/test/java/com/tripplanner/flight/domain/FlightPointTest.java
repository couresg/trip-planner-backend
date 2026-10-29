package com.tripplanner.flight.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlightPointTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Test
    @DisplayName("revised가 있으면 계산 기준 시각은 revised다")
    void revisedFirst() {
        FlightPoint point = new FlightPoint("KIX", TOKYO, Instant.parse("2026-11-05T04:45:00Z"),
                Instant.parse("2026-11-05T04:52:00Z"), "1");

        assertThat(point.effectiveTime()).isEqualTo(Instant.parse("2026-11-05T04:52:00Z"));
    }

    @Test
    @DisplayName("revised가 없으면 계산 기준 시각은 scheduled다")
    void scheduledWhenNoRevised() {
        FlightPoint point = new FlightPoint("KIX", TOKYO, Instant.parse("2026-11-05T04:45:00Z"), null, null);

        assertThat(point.effectiveTime()).isEqualTo(Instant.parse("2026-11-05T04:45:00Z"));
        assertThat(point.revisedLocal()).isNull();
    }

    @Test
    @DisplayName("UTC 15:20은 도쿄 현지 시각으로 다음 날 00:20(+09:00)이다")
    void localTimeCrossesMidnight() {
        FlightPoint point = new FlightPoint("KIX", TOKYO, Instant.parse("2026-11-05T15:20:00Z"), null, "1");

        assertThat(point.scheduledLocal()).isEqualTo(OffsetDateTime.parse("2026-11-06T00:20:00+09:00"));
    }
}
