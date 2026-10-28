package com.tripplanner.catalog.repository;

import com.tripplanner.catalog.domain.City;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CityRepository extends JpaRepository<City, Long> {

    List<City> findAllByActiveTrueOrderByIdAsc();

    Optional<City> findByCodeAndActiveTrue(String code);
}
