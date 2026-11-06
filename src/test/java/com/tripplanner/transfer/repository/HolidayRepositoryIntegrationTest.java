package com.tripplanner.transfer.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.transfer.domain.Holiday;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Flyway V3 holiday 스키마 + V106 일본 공휴일 시드를 검증한다. */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class HolidayRepositoryIntegrationTest {

    @Autowired
    HolidayRepository holidayRepository;

    @Test
    @DisplayName("일본 공휴일은 2026년 18일, 2027년 17일(대체 휴일·국민의 휴일 포함)이 시드되어 있고 2028년은 없다")
    void japaneseHolidaySeed() {
        assertThat(holidayRepository.findAllByCountryCodeAndHolidayDateBetween("JP", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31))).hasSize(18)
                .extracting(Holiday::getHolidayDate, Holiday::getName)
                .contains(tuple(LocalDate.of(2026, 11, 23), "勤労感謝の日"),
                        tuple(LocalDate.of(2026, 5, 6), "休日"), tuple(LocalDate.of(2026, 9, 22), "休日"));
        assertThat(holidayRepository.findAllByCountryCodeAndHolidayDateBetween("JP", LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31))).hasSize(17)
                .extracting(Holiday::getHolidayDate, Holiday::getName)
                .contains(tuple(LocalDate.of(2027, 3, 22), "休日"));
        assertThat(holidayRepository.findAllByCountryCodeAndHolidayDateBetween("JP", LocalDate.of(2028, 1, 1),
                LocalDate.of(2028, 12, 31))).isEmpty();
    }
}
