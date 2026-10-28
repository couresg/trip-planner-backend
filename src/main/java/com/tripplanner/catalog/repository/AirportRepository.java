package com.tripplanner.catalog.repository;

import com.tripplanner.catalog.domain.Airport;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AirportRepository extends JpaRepository<Airport, Long> {

    List<Airport> findAllByCityIdInOrderByIataAsc(Collection<Long> cityIds);
}
