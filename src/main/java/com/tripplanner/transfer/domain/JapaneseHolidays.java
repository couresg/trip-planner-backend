package com.tripplanner.transfer.domain;

import java.time.LocalDate;
import java.util.Set;

/**
 * 일본 국민의 축일·휴일 (대체 휴일·국민의 휴일 포함). 2026~2027 고정 목록이며 그 밖의 해는 없는 것으로 본다.
 * 출처: 내각부 https://www8.cao.go.jp/chosei/shukujitsu/syukujitsu.csv (2026-11-03 조회)
 */
final class JapaneseHolidays {

    private static final Set<LocalDate> DATES = Set.of(
            LocalDate.of(2026, 1, 1), // 元日
            LocalDate.of(2026, 1, 12), // 成人の日
            LocalDate.of(2026, 2, 11), // 建国記念の日
            LocalDate.of(2026, 2, 23), // 天皇誕生日
            LocalDate.of(2026, 3, 20), // 春分の日
            LocalDate.of(2026, 4, 29), // 昭和の日
            LocalDate.of(2026, 5, 3), // 憲法記念日
            LocalDate.of(2026, 5, 4), // みどりの日
            LocalDate.of(2026, 5, 5), // こどもの日
            LocalDate.of(2026, 5, 6), // 休日
            LocalDate.of(2026, 7, 20), // 海の日
            LocalDate.of(2026, 8, 11), // 山の日
            LocalDate.of(2026, 9, 21), // 敬老の日
            LocalDate.of(2026, 9, 22), // 休日
            LocalDate.of(2026, 9, 23), // 秋分の日
            LocalDate.of(2026, 10, 12), // スポーツの日
            LocalDate.of(2026, 11, 3), // 文化の日
            LocalDate.of(2026, 11, 23), // 勤労感謝の日
            LocalDate.of(2027, 1, 1), // 元日
            LocalDate.of(2027, 1, 11), // 成人の日
            LocalDate.of(2027, 2, 11), // 建国記念の日
            LocalDate.of(2027, 2, 23), // 天皇誕生日
            LocalDate.of(2027, 3, 21), // 春分の日
            LocalDate.of(2027, 3, 22), // 休日
            LocalDate.of(2027, 4, 29), // 昭和の日
            LocalDate.of(2027, 5, 3), // 憲法記念日
            LocalDate.of(2027, 5, 4), // みどりの日
            LocalDate.of(2027, 5, 5), // こどもの日
            LocalDate.of(2027, 7, 19), // 海の日
            LocalDate.of(2027, 8, 11), // 山の日
            LocalDate.of(2027, 9, 20), // 敬老の日
            LocalDate.of(2027, 9, 23), // 秋分の日
            LocalDate.of(2027, 10, 11), // スポーツの日
            LocalDate.of(2027, 11, 3), // 文化の日
            LocalDate.of(2027, 11, 23) // 勤労感謝の日
    );

    private JapaneseHolidays() {
    }

    static boolean contains(LocalDate date) {
        return DATES.contains(date);
    }
}
