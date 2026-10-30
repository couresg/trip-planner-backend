package com.tripplanner.transfer.domain;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 운행일 기준 분 (minute-of-service-day). 자정을 넘긴 시각은 1440 이상이다 (00:30 → 1470).
 * 실제 시각 = 운행일 현지 벽시계 00:00 + 분 (LocalDateTime 계산 후 timezone 적용, DST 전환일에도 표기 시각 유지).
 * 2880분(익익일 00:00) 이상은 지원하지 않는다 (전날 운행일까지만 거슬러 확인하므로).
 */
public record ServiceMinute(int value) implements Comparable<ServiceMinute> {

    public ServiceMinute {
        if (value < 0) {
            throw new IllegalArgumentException("운행일 기준 분은 0 이상이어야 합니다: " + value);
        }
    }

    public static ServiceMinute of(int value) {
        return new ServiceMinute(value);
    }

    public ServiceMinute plus(int minutes) {
        return new ServiceMinute(value + minutes);
    }

    public ZonedDateTime on(LocalDate serviceDate, ZoneId zone) {
        return serviceDate.atStartOfDay().plusMinutes(value).atZone(zone);
    }

    @Override
    public int compareTo(ServiceMinute other) {
        return Integer.compare(value, other.value);
    }
}
