package com.tripplanner.itinerary.repository;

import com.tripplanner.itinerary.domain.ItineraryItem;
import com.tripplanner.trip.domain.Trip;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem, Long> {

    @EntityGraph(attributePaths = "place")
    List<ItineraryItem> findAllByTripOrderByVisitDateAscPositionAscIdAsc(Trip trip);

    List<ItineraryItem> findAllByTripAndVisitDateOrderByPositionAscIdAsc(Trip trip, LocalDate visitDate);

    @EntityGraph(attributePaths = "place")
    Optional<ItineraryItem> findByIdAndTrip(Long id, Trip trip);

    /** 날짜에 항목이 없으면 -1 */
    @Query("""
            select coalesce(max(i.position), -1) from ItineraryItem i
            where i.trip = :trip and i.visitDate = :visitDate
            """)
    int findMaxPosition(@Param("trip") Trip trip, @Param("visitDate") LocalDate visitDate);
}
