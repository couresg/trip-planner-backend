package com.tripplanner.itinerary.domain;

import com.tripplanner.place.domain.Place;
import com.tripplanner.trip.domain.Trip;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.hibernate.annotations.JdbcType;
import org.hibernate.type.descriptor.jdbc.LocalTimeJdbcType;

/** 여행의 일자별 방문 장소. plannedTime은 도시 현지 시각이고 position은 날짜 안 순서(0부터)다. */
@Entity
@Table(name = "itinerary_item")
public class ItineraryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "visit_date", nullable = false)
    private LocalDate visitDate;

    @Column(nullable = false)
    private int position;

    /** 벽시계 시각이다. 기본 매핑은 hibernate.jdbc.time_zone(UTC)으로 변환해 10:30이 01:30으로 저장되므로 변환 없이 바인딩한다. */
    @JdbcType(LocalTimeJdbcType.class)
    @Column(name = "planned_time")
    private LocalTime plannedTime;

    @Column(length = 500)
    private String memo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ItineraryItem() {
    }

    public ItineraryItem(Trip trip, Place place, LocalDate visitDate, int position, LocalTime plannedTime, String memo,
            Instant now) {
        this.trip = trip;
        this.place = place;
        this.visitDate = visitDate;
        this.position = position;
        this.plannedTime = plannedTime;
        this.memo = memo;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void edit(LocalTime plannedTime, String memo, Instant now) {
        this.plannedTime = plannedTime;
        this.memo = memo;
        this.updatedAt = now;
    }

    /** 날짜·순서가 그대로면 아무것도 바꾸지 않는다 (재정렬 때 불필요한 UPDATE 방지). */
    public void moveTo(LocalDate visitDate, int position, Instant now) {
        if (this.visitDate.equals(visitDate) && this.position == position) {
            return;
        }
        this.visitDate = visitDate;
        this.position = position;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public Place getPlace() {
        return place;
    }

    public LocalDate getVisitDate() {
        return visitDate;
    }

    public int getPosition() {
        return position;
    }

    public LocalTime getPlannedTime() {
        return plannedTime;
    }

    public String getMemo() {
        return memo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
