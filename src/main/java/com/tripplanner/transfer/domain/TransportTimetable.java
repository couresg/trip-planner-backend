package com.tripplanner.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** TIMETABLE 노선의 출발 시각 한 행 (운행일 기준 분). */
@Entity
@Table(name = "transport_timetable")
public class TransportTimetable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private TransportRoute route;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_type", nullable = false, length = 10)
    private DayType dayType;

    @Column(name = "departure_min", nullable = false)
    private int departureMin;

    protected TransportTimetable() {
    }

    public ServiceMinute departure() {
        return ServiceMinute.of(departureMin);
    }

    public Long getId() {
        return id;
    }

    public TransportRoute getRoute() {
        return route;
    }

    public DayType getDayType() {
        return dayType;
    }

    public int getDepartureMin() {
        return departureMin;
    }
}
