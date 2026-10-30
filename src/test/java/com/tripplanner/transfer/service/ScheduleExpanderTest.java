package com.tripplanner.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.transfer.domain.DayType;
import com.tripplanner.transfer.domain.Schedule;
import com.tripplanner.transfer.domain.ScheduleType;
import com.tripplanner.transfer.domain.ServiceMinute;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2026-11-05 목 / 11-06 금 / 11-07 토 / 11-08 일 (Asia/Tokyo) */
class ScheduleExpanderTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    /** 난카이 공항급행 시드: 05:45~23:35, 30분 간격 */
    private static final Schedule HEADWAY = Schedule.headway(345, 1415, 30);

    private final ScheduleExpander expander = new ScheduleExpander();

    @Test
    @DisplayName("배차간격 노선은 첫차부터 간격만큼 더한 시각 중 조회 범위 안의 출발을 반환한다")
    void headway() {
        assertThat(expander.departures(HEADWAY, at(5, 14, 0), at(5, 16, 0)))
                .containsExactly(at(5, 14, 15), at(5, 14, 45), at(5, 15, 15), at(5, 15, 45));
    }

    @Test
    @DisplayName("배차간격 노선은 막차를 넘는 시각을 만들지 않는다")
    void headwayStopsAtLast() {
        assertThat(expander.departures(Schedule.headway(345, 1400, 30), at(5, 23, 0), at(6, 1, 0)))
                .containsExactly(at(5, 23, 15));
        assertThat(expander.departures(Schedule.headway(345, 1395, 30), at(5, 23, 0), at(6, 1, 0)))
                .containsExactly(at(5, 23, 15));
    }

    @Test
    @DisplayName("조회 범위의 시작과 끝 시각에 딱 맞는 출발도 포함한다")
    void inclusiveBoundaries() {
        assertThat(expander.departures(HEADWAY, at(5, 14, 15), at(5, 15, 45)))
                .containsExactly(at(5, 14, 15), at(5, 14, 45), at(5, 15, 15), at(5, 15, 45));
        assertThat(expander.departures(HEADWAY, at(5, 14, 16), at(5, 15, 44)))
                .containsExactly(at(5, 14, 45), at(5, 15, 15));
    }

    @Test
    @DisplayName("시각표 노선은 운행일의 요일 유형(평일·토요일·휴일) 행을 쓴다")
    void timetableByDayType() {
        Schedule schedule = timetable(Map.of(
                DayType.WEEKDAY, minutes(600),
                DayType.SATURDAY, minutes(610),
                DayType.HOLIDAY, minutes(620)));

        assertThat(expander.departures(schedule, at(6, 9, 0), at(6, 11, 0))).containsExactly(at(6, 10, 0));
        assertThat(expander.departures(schedule, at(7, 9, 0), at(7, 11, 0))).containsExactly(at(7, 10, 10));
        assertThat(expander.departures(schedule, at(8, 9, 0), at(8, 11, 0))).containsExactly(at(8, 10, 20));
    }

    @Test
    @DisplayName("23시30분부터 01시30분까지 조회하면 전날 운행일의 자정 넘김 분(1470)도 찾는다")
    void windowCrossingMidnightFindsPreviousServiceDay() {
        Schedule schedule = timetable(Map.of(DayType.WEEKDAY, minutes(1380, 1410, 1470, 1530, 1560)));

        assertThat(expander.departures(schedule, at(5, 23, 30), at(6, 1, 30)))
                .containsExactly(at(5, 23, 30), at(6, 0, 30), at(6, 1, 30));
    }

    @Test
    @DisplayName("자정 이후 시작하는 조회 범위도 전날 운행일의 야간분을 찾는다")
    void windowAfterMidnightFindsPreviousServiceDay() {
        Schedule schedule = timetable(Map.of(DayType.WEEKDAY, minutes(1470)));

        assertThat(expander.departures(schedule, at(6, 0, 10), at(6, 2, 10))).containsExactly(at(6, 0, 30));
    }

    @Test
    @DisplayName("금요일 밤 → 토요일 새벽: 금요일 운행일의 1470분은 평일 시각표, 토요일 운행일은 토요일 시각표를 쓴다")
    void fridayNightToSaturdayUsesServiceDayType() {
        Schedule schedule = timetable(Map.of(
                DayType.WEEKDAY, minutes(60, 1470),
                DayType.SATURDAY, minutes(90, 1480),
                DayType.HOLIDAY, minutes(100)));

        // 토 00:30 = 금 운행일(WEEKDAY) 1470, 토 01:30 = 토 운행일(SATURDAY) 90. 토 01:00(WEEKDAY 60)은 없다.
        assertThat(expander.departures(schedule, at(7, 0, 0), at(7, 2, 0)))
                .containsExactly(at(7, 0, 30), at(7, 1, 30));
        // 일 00:40 = 토 운행일(SATURDAY) 1480, 일 01:40 = 일 운행일(HOLIDAY) 100
        assertThat(expander.departures(schedule, at(8, 0, 0), at(8, 2, 0)))
                .containsExactly(at(8, 0, 40), at(8, 1, 40));
    }

    @Test
    @DisplayName("시간표가 없는 노선(NONE)과 배차간격 설정이 빠진 노선은 출발이 없다")
    void noneIsEmpty() {
        assertThat(expander.departures(Schedule.none(), at(5, 0, 0), at(5, 23, 59))).isEmpty();
        assertThat(expander.departures(new Schedule(ScheduleType.HEADWAY, ServiceMinute.of(345), null, 30, null),
                at(5, 0, 0), at(5, 23, 59))).isEmpty();
        assertThat(expander.nextDepartureFrom(Schedule.none(), at(5, 12, 0))).isEmpty();
    }

    @Test
    @DisplayName("끝이 시작보다 빠르면 빈 목록이다")
    void reversedWindow() {
        assertThat(expander.departures(HEADWAY, at(5, 16, 0), at(5, 14, 0))).isEmpty();
    }

    @Test
    @DisplayName("다음 출발은 기준 시각 이후(같은 시각 포함)의 가장 이른 출발이며 막차 이후면 다음 날 첫차다")
    void nextDepartureFrom() {
        assertThat(expander.nextDepartureFrom(HEADWAY, at(5, 14, 15))).contains(at(5, 14, 15));
        assertThat(expander.nextDepartureFrom(HEADWAY, at(5, 14, 16))).contains(at(5, 14, 45));
        assertThat(expander.nextDepartureFrom(HEADWAY, at(5, 23, 50))).contains(at(6, 5, 45));
        assertThat(expander.nextDepartureFrom(HEADWAY, at(6, 2, 0))).contains(at(6, 5, 45));
    }

    @Test
    @DisplayName("다음 출발은 자정 넘김 분과 요일 유형을 반영하고, 3일 뒤 운행일까지만 찾는다")
    void nextDepartureAcrossDays() {
        Schedule nightOnly = timetable(Map.of(DayType.WEEKDAY, minutes(1470)));
        assertThat(expander.nextDepartureFrom(nightOnly, at(6, 0, 10))).contains(at(6, 0, 30));

        Schedule saturdayOnly = timetable(Map.of(DayType.SATURDAY, minutes(600)));
        assertThat(expander.nextDepartureFrom(saturdayOnly, at(5, 12, 0))).contains(at(7, 10, 0));

        // 월(11-09) 12:00 이후 → 화·수·목 운행일만 확인하므로 일요일(HOLIDAY) 시각은 찾지 않는다
        Schedule holidayOnly = timetable(Map.of(DayType.HOLIDAY, minutes(600)));
        assertThat(expander.nextDepartureFrom(holidayOnly, at(9, 12, 0))).isEmpty();
    }

    @Test
    @DisplayName("요일 유형: 토요일은 SATURDAY, 일요일은 HOLIDAY, 나머지는 WEEKDAY")
    void dayTypeOf() {
        assertThat(IntStream.rangeClosed(2, 8).mapToObj(day -> DayType.of(at(day, 12, 0).toLocalDate())))
                .containsExactly(DayType.WEEKDAY, DayType.WEEKDAY, DayType.WEEKDAY, DayType.WEEKDAY,
                        DayType.WEEKDAY, DayType.SATURDAY, DayType.HOLIDAY);
    }

    private static ZonedDateTime at(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, TOKYO);
    }

    private static Schedule timetable(Map<DayType, List<ServiceMinute>> rows) {
        return Schedule.timetable(rows);
    }

    private static List<ServiceMinute> minutes(int... values) {
        return Arrays.stream(values).mapToObj(ServiceMinute::of).toList();
    }
}
