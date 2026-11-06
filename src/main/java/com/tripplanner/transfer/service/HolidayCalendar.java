package com.tripplanner.transfer.service;

import com.tripplanner.transfer.domain.DayType;
import com.tripplanner.transfer.domain.Holiday;
import com.tripplanner.transfer.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 나라별 공휴일로 운행일의 요일 유형을 판정한다. holiday 테이블을 (나라, 연도) 단위로 처음 쓸 때 한 번 읽어 메모리에 둔다
 * (시드로만 바뀌므로 재기동 전까지 유지). 데이터가 없는 (나라, 연도)는 요일로만 판정하고 WARN을 한 번 남긴다.
 */
@Component
public class HolidayCalendar {

    private static final Logger log = LoggerFactory.getLogger(HolidayCalendar.class);

    private final HolidayRepository holidayRepository;
    private final Map<CountryYear, Set<LocalDate>> cache = new ConcurrentHashMap<>();

    public HolidayCalendar(HolidayRepository holidayRepository) {
        this.holidayRepository = holidayRepository;
    }

    /** @param countryCode ISO 3166-1 alpha-2 (도시의 country_code) */
    public DayType dayType(String countryCode, LocalDate serviceDate) {
        return DayType.of(serviceDate, isHoliday(countryCode, serviceDate));
    }

    public boolean isHoliday(String countryCode, LocalDate date) {
        return cache.computeIfAbsent(new CountryYear(countryCode, date.getYear()), this::load).contains(date);
    }

    private Set<LocalDate> load(CountryYear key) {
        Set<LocalDate> dates = holidayRepository.findAllByCountryCodeAndHolidayDateBetween(key.countryCode(),
                        LocalDate.of(key.year(), 1, 1), LocalDate.of(key.year(), 12, 31)).stream()
                .map(Holiday::getHolidayDate)
                .collect(Collectors.toUnmodifiableSet());
        if (dates.isEmpty()) {
            log.warn("공휴일 데이터 없음: country={}, year={} → 요일로만 판정한다", key.countryCode(), key.year());
        }
        return dates;
    }

    private record CountryYear(String countryCode, int year) {
    }
}
