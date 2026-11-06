package com.tripplanner.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DayTypeTest {

    @ParameterizedTest(name = "{0} holiday={1} = {2}")
    @DisplayName("공휴일이면 요일과 상관없이 HOLIDAY이고, 아니면 토요일 SATURDAY, 일요일 HOLIDAY, 나머지 WEEKDAY다")
    @CsvSource({
            "2026-11-23, true, HOLIDAY", // 월요일 공휴일
            "2026-11-24, false, WEEKDAY",
            "2026-11-07, false, SATURDAY",
            "2026-11-07, true, HOLIDAY", // 토요일 공휴일
            "2026-11-08, false, HOLIDAY"
    })
    void of(LocalDate date, boolean holiday, DayType expected) {
        assertThat(DayType.of(date, holiday)).isEqualTo(expected);
    }
}
