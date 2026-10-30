package com.tripplanner.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServiceMinuteTest {

    @Test
    @DisplayName("운행일 기준 분은 현지 벽시계 시각이며 1440 이상은 다음 날 시각이다")
    void wallClock() {
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        LocalDate friday = LocalDate.of(2026, 11, 6);

        assertThat(ServiceMinute.of(345).on(friday, tokyo).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 11, 6, 5, 45));
        assertThat(ServiceMinute.of(1470).on(friday, tokyo).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 11, 7, 0, 30));
    }

    @Test
    @DisplayName("DST 시작일(뉴욕 2026-03-08)에도 경과 분이 아니라 표기 시각(03:00)을 지킨다")
    void dstKeepsWallClock() {
        ZoneId newYork = ZoneId.of("America/New_York");

        assertThat(ServiceMinute.of(180).on(LocalDate.of(2026, 3, 8), newYork).toLocalDateTime())
                .isEqualTo(LocalDateTime.of(2026, 3, 8, 3, 0));
    }

    @Test
    @DisplayName("음수 분은 허용하지 않는다")
    void negative() {
        assertThatThrownBy(() -> ServiceMinute.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
