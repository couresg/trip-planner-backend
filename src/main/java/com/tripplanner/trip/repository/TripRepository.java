package com.tripplanner.trip.repository;

import com.tripplanner.trip.domain.Trip;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<Trip, UUID> {

    @EntityGraph(attributePaths = {"city", "area", "flights"})
    List<Trip> findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(Collection<String> ownerTokenHashes);

    @EntityGraph(attributePaths = {"city", "area", "flights"})
    Optional<Trip> findWithDetailsById(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> lockById(@Param("id") UUID id);
}
