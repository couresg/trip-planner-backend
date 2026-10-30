package com.tripplanner.transfer.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 노선의 운행 계획. 운행일의 요일 유형별 출발 시각(운행일 기준 분)을 낸다. */
public record Schedule(
        ScheduleType type,
        ServiceMinute first,
        ServiceMinute last,
        Integer headwayMin,
        Map<DayType, List<ServiceMinute>> timetable) {

    public Schedule {
        timetable = timetable == null ? Map.of() : Map.copyOf(timetable);
    }

    public static Schedule none() {
        return new Schedule(ScheduleType.NONE, null, null, null, Map.of());
    }

    public static Schedule headway(int firstMin, int lastMin, int headwayMin) {
        return new Schedule(ScheduleType.HEADWAY, ServiceMinute.of(firstMin), ServiceMinute.of(lastMin), headwayMin,
                Map.of());
    }

    public static Schedule timetable(Map<DayType, List<ServiceMinute>> timetable) {
        return new Schedule(ScheduleType.TIMETABLE, null, null, null, timetable);
    }

    /** HEADWAY: first, first+h, ... ≤ last (요일 무관). TIMETABLE: 해당 요일 유형 행. NONE·설정 누락: 없음. */
    public List<ServiceMinute> minutesOn(DayType dayType) {
        return switch (type) {
            case TIMETABLE -> timetable.getOrDefault(dayType, List.of());
            case HEADWAY -> headwayMinutes();
            case NONE -> List.of();
        };
    }

    private List<ServiceMinute> headwayMinutes() {
        if (first == null || last == null || headwayMin == null || headwayMin <= 0) {
            return List.of();
        }
        List<ServiceMinute> minutes = new ArrayList<>();
        for (ServiceMinute m = first; m.compareTo(last) <= 0; m = m.plus(headwayMin)) {
            minutes.add(m);
        }
        return minutes;
    }
}
