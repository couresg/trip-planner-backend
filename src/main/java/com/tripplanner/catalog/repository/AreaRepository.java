package com.tripplanner.catalog.repository;

import com.tripplanner.catalog.domain.Area;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AreaRepository extends JpaRepository<Area, Long> {

    List<Area> findAllByCityIdOrderBySortOrderAscIdAsc(Long cityId);
}
