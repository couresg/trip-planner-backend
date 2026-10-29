package com.tripplanner.trip.repository;

import com.tripplanner.trip.domain.Trip;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    @EntityGraph(attributePaths = {"city", "area", "flights"})
    List<Trip> findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(Collection<String> ownerTokenHashes);

    @EntityGraph(attributePaths = {"city", "area", "flights"})
    Optional<Trip> findWithDetailsById(UUID id);
}
