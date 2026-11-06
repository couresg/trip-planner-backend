package com.tripplanner.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tripplanner.transfer.domain.DayType;
import com.tripplanner.transfer.domain.Holiday;
import com.tripplanner.transfer.repository.HolidayRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class HolidayCalendarTest {

    private final HolidayRepository repository = mock(HolidayRepository.class);
    private final HolidayCalendar calendar = new HolidayCalendar(repository);
    private final Logger logger = (Logger) LoggerFactory.getLogger(HolidayCalendar.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        given(repository.findAllByCountryCodeAndHolidayDateBetween(anyString(), any(), any())).willReturn(List.of());
        given(repository.findAllByCountryCodeAndHolidayDateBetween("JP", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31))).willReturn(List.of(new Holiday("JP", LocalDate.of(2026, 11, 23),
                "勤労感謝の日")));
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("평일에 걸린 그 나라 공휴일은 HOLIDAY이고, 다른 나라·다른 날은 요일로 판정한다")
    void dayType() {
        assertThat(calendar.dayType("JP", LocalDate.of(2026, 11, 23))).isEqualTo(DayType.HOLIDAY);
        assertThat(calendar.dayType("JP", LocalDate.of(2026, 11, 24))).isEqualTo(DayType.WEEKDAY);
        assertThat(calendar.dayType("JP", LocalDate.of(2026, 11, 28))).isEqualTo(DayType.SATURDAY);
        assertThat(calendar.dayType("JP", LocalDate.of(2026, 11, 29))).isEqualTo(DayType.HOLIDAY);
        assertThat(calendar.dayType("KR", LocalDate.of(2026, 11, 23))).isEqualTo(DayType.WEEKDAY);
    }

    @Test
    @DisplayName("(나라, 연도)마다 한 번만 조회해 메모리에 두고, 데이터가 없는 해는 요일로 판정하며 WARN을 한 번 남긴다")
    void loadsOncePerCountryYearAndWarnsWhenEmpty() {
        calendar.isHoliday("JP", LocalDate.of(2026, 1, 1));
        calendar.isHoliday("JP", LocalDate.of(2026, 11, 23));
        assertThat(calendar.dayType("JP", LocalDate.of(2028, 11, 23))).isEqualTo(DayType.WEEKDAY);
        assertThat(calendar.dayType("JP", LocalDate.of(2028, 11, 26))).isEqualTo(DayType.HOLIDAY);

        verify(repository, times(1)).findAllByCountryCodeAndHolidayDateBetween(eq("JP"),
                eq(LocalDate.of(2026, 1, 1)), eq(LocalDate.of(2026, 12, 31)));
        verify(repository, times(1)).findAllByCountryCodeAndHolidayDateBetween(eq("JP"),
                eq(LocalDate.of(2028, 1, 1)), eq(LocalDate.of(2028, 12, 31)));
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("country=JP", "year=2028");
        });
    }
}
