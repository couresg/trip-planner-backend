package com.tripplanner.transfer.repository;

import com.tripplanner.transfer.domain.RouteDirection;
import com.tripplanner.transfer.domain.TransportRoute;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransportRouteRepository extends JpaRepository<TransportRoute, Long> {

    @EntityGraph(attributePaths = "timetable")
    List<TransportRoute> findAllByAirportIdAndAreaIdAndDirectionAndActiveTrueOrderByIdAsc(Long airportId,
            Long areaId, RouteDirection direction);
}
