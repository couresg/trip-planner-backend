package com.tripplanner.trip.repository;

import com.tripplanner.trip.domain.TripFlight;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripFlightRepository extends JpaRepository<TripFlight, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from TripFlight f where f.id = :id")
    Optional<TripFlight> lockById(@Param("id") Long id);
}
