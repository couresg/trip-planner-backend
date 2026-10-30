package com.tripplanner.transfer.service;

import com.tripplanner.transfer.domain.DayType;
import com.tripplanner.transfer.domain.Schedule;
import com.tripplanner.transfer.domain.ServiceMinute;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 노선 운행 계획을 실제 출발 시각(공항 timezone)으로 펼친다.
 * 출발 시각 = 운행일 00:00(현지) + 운행일 기준 분, 요일 유형은 운행일 기준이다
 * (금요일 운행일의 1470분 = 토요일 00:30이지만 WEEKDAY 시각표를 쓴다).
 */
@Component
public class ScheduleExpander {

    /** 다음 첫차를 찾을 때 기준 날짜 이후로 살펴볼 운행일 수 */
    static final int NEXT_DEPARTURE_SEARCH_DAYS = 3;

    /**
     * [start, end] (양 끝 포함) 안의 출발 시각을 오름차순으로 반환한다. 시각은 start의 timezone으로 표현한다.
     * 전날 운행일의 자정 넘김 분(예: 1470)을 찾기 위해 start 날짜 - 1일부터 end 날짜까지의 운행일을 확인한다.
     */
    public List<ZonedDateTime> departures(Schedule schedule, ZonedDateTime start, ZonedDateTime end) {
        ZoneId zone = start.getZone();
        ZonedDateTime endInZone = end.withZoneSameInstant(zone);
        if (endInZone.isBefore(start)) {
            return List.of();
        }
        return expand(schedule, zone, start.toLocalDate().minusDays(1), endInZone.toLocalDate()).stream()
                .filter(dep -> !dep.isBefore(start) && !dep.isAfter(endInZone))
                .toList();
    }

    /** from 이후(같은 시각 포함) 가장 이른 출발. 운행일 from 날짜 - 1일 ~ + 3일 안에서 찾는다. */
    public Optional<ZonedDateTime> nextDepartureFrom(Schedule schedule, ZonedDateTime from) {
        LocalDate date = from.toLocalDate();
        return expand(schedule, from.getZone(), date.minusDays(1), date.plusDays(NEXT_DEPARTURE_SEARCH_DAYS))
                .stream()
                .filter(dep -> !dep.isBefore(from))
                .findFirst();
    }

    private static List<ZonedDateTime> expand(Schedule schedule, ZoneId zone, LocalDate fromServiceDate,
            LocalDate toServiceDate) {
        List<ZonedDateTime> result = new ArrayList<>();
        for (LocalDate serviceDate = fromServiceDate; !serviceDate.isAfter(toServiceDate);
                serviceDate = serviceDate.plusDays(1)) {
            for (ServiceMinute minute : schedule.minutesOn(DayType.of(serviceDate))) {
                result.add(minute.on(serviceDate, zone));
            }
        }
        return result.stream().sorted().distinct().toList();
    }
}
