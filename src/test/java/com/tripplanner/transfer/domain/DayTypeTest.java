package com.tripplanner.transfer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DayTypeTest {

    @ParameterizedTest(name = "{0} = {1}")
    @DisplayName("평일에 걸린 공휴일·대체 휴일·국민의 휴일은 HOLIDAY이고, 그 밖의 날은 요일로 판정한다")
    @CsvSource({
            "2026-11-23, HOLIDAY", // 월요일 근로감사의 날
            "2026-05-06, HOLIDAY", // 수요일 대체 휴일
            "2026-09-22, HOLIDAY", // 화요일 국민의 휴일
            "2027-03-22, HOLIDAY", // 월요일 대체 휴일
            "2027-11-23, HOLIDAY", // 목록 마지막 날
            "2026-11-24, WEEKDAY", // 공휴일 다음 날
            "2026-11-07, SATURDAY",
            "2026-11-08, HOLIDAY",
            "2025-11-24, WEEKDAY", // 목록 밖: 실제 대체 휴일이지만 2025는 목록에 없다
            "2028-11-23, WEEKDAY", // 목록 밖: 목요일 공휴일이지만 2028은 목록에 없다
            "2028-11-25, SATURDAY",
            "2028-11-26, HOLIDAY"
    })
    void of(LocalDate date, DayType expected) {
        assertThat(DayType.of(date)).isEqualTo(expected);
    }
}
