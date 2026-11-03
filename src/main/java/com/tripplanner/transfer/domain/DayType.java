package com.tripplanner.transfer.domain;

import java.time.LocalDate;

/** 시각표 요일 유형. 일본 공휴일(2026~2027 고정 목록)과 일요일이 HOLIDAY다. */
public enum DayType {
    WEEKDAY,
    SATURDAY,
    HOLIDAY;

    /** 운행일(service day) 기준 요일 유형 */
    public static DayType of(LocalDate serviceDate) {
        if (JapaneseHolidays.contains(serviceDate)) {
            return HOLIDAY;
        }
        return switch (serviceDate.getDayOfWeek()) {
            case SATURDAY -> SATURDAY;
            case SUNDAY -> HOLIDAY;
            default -> WEEKDAY;
        };
    }
}
