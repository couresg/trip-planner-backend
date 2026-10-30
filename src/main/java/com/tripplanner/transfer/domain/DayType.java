package com.tripplanner.transfer.domain;

import java.time.LocalDate;

/** 시각표 요일 유형. 공휴일 테이블이 아직 없어 일요일만 HOLIDAY로 본다. */
public enum DayType {
    WEEKDAY,
    SATURDAY,
    HOLIDAY;

    /** 운행일(service day) 기준 요일 유형 */
    public static DayType of(LocalDate serviceDate) {
        return switch (serviceDate.getDayOfWeek()) {
            case SATURDAY -> SATURDAY;
            case SUNDAY -> HOLIDAY;
            default -> WEEKDAY;
        };
    }
}
