package com.tripplanner.transfer.repository;

import com.tripplanner.transfer.domain.Holiday;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {

    List<Holiday> findAllByCountryCodeAndHolidayDateBetween(String countryCode, LocalDate from, LocalDate to);
}
