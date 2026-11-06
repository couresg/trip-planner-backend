package com.tripplanner.transfer.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.tripplanner.transfer.domain.Holiday;
import com.tripplanner.transfer.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.Arrays;

/** 단위 테스트용 HolidayCalendar. 리포지토리 목이 주어진 나라의 공휴일만 기간으로 걸러 돌려준다. */
final class TestHolidayCalendars {

    /** 단위 테스트가 쓰는 일본 공휴일: 화 2026-11-03 문화의 날, 월 2026-11-23 근로감사의 날 */
    static final String JP = "JP";

    private TestHolidayCalendars() {
    }

    static HolidayCalendar japan2026() {
        return of(JP, LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 23));
    }

    static HolidayCalendar of(String countryCode, LocalDate... dates) {
        HolidayRepository repository = mock(HolidayRepository.class);
        given(repository.findAllByCountryCodeAndHolidayDateBetween(anyString(), any(), any())).willAnswer(call -> {
            String country = call.getArgument(0);
            LocalDate from = call.getArgument(1);
            LocalDate to = call.getArgument(2);
            return Arrays.stream(dates)
                    .filter(date -> country.equals(countryCode) && !date.isBefore(from) && !date.isAfter(to))
                    .map(date -> new Holiday(countryCode, date, "test"))
                    .toList();
        });
        return new HolidayCalendar(repository);
    }
}
