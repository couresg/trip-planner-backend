package com.tripplanner.transfer.domain;

/** TIMETABLE: transport_timetable 시각표, HEADWAY: 첫차·막차·배차간격, NONE: 시간표 없음(택시 등) */
public enum ScheduleType {
    TIMETABLE,
    HEADWAY,
    NONE
}
