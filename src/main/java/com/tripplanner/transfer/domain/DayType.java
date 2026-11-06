package com.tripplanner.transfer.domain;

import java.time.LocalDate;

/** 시각표 요일 유형. 공휴일(holiday 테이블)과 일요일이 HOLIDAY다. */
public enum DayType {
    WEEKDAY,
    SATURDAY,
    HOLIDAY;

    /** 운행일(service day) 기준 요일 유형. holiday는 운행일이 그 나라 공휴일인지다. */
    public static DayType of(LocalDate serviceDate, boolean holiday) {
        if (holiday) {
            return HOLIDAY;
        }
        return switch (serviceDate.getDayOfWeek()) {
            case SATURDAY -> SATURDAY;
            case SUNDAY -> HOLIDAY;
            default -> WEEKDAY;
        };
    }
}
