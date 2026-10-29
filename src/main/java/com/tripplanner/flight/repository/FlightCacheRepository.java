package com.tripplanner.flight.repository;

import com.tripplanner.flight.domain.FlightCacheEntry;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlightCacheRepository extends JpaRepository<FlightCacheEntry, Long> {

    Optional<FlightCacheEntry> findByFlightNoAndFlightDate(String flightNo, LocalDate flightDate);
}
