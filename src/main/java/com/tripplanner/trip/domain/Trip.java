package com.tripplanner.trip.domain;

import com.tripplanner.catalog.domain.Area;
import com.tripplanner.catalog.domain.City;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * 여행. 소유 토큰은 원문 대신 SHA-256 해시만 저장한다.
 * ID는 생성 시 정하므로 Persistable로 새 엔티티임을 알려 save가 merge(선조회) 대신 persist하게 한다.
 */
@Entity
@Table(name = "trip")
public class Trip implements Persistable<UUID> {

    @Id
    @Column(columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "owner_token_hash", nullable = false, columnDefinition = "char(64)")
    private String ownerTokenHash;

    @Column(name = "user_id")
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TripFlight> flights = new ArrayList<>();

    @Transient
    private boolean isNew = true;

    protected Trip() {
    }

    public Trip(String ownerTokenHash, City city, Area area, LocalDate startDate, LocalDate endDate, Instant now) {
        this.id = UUID.randomUUID();
        this.ownerTokenHash = ownerTokenHash;
        this.city = city;
        this.area = area;
        this.startDate = startDate;
        this.endDate = endDate;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void addFlight(TripFlight flight) {
        flight.assignTo(this);
        flights.add(flight);
    }

    public TripFlight flight(FlightDirection direction) {
        return flights.stream()
                .filter(f -> f.getDirection() == direction)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여행 항공편 누락: " + direction));
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public String getOwnerTokenHash() {
        return ownerTokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public City getCity() {
        return city;
    }

    public Area getArea() {
        return area;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<TripFlight> getFlights() {
        return flights;
    }
}
