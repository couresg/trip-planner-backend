package com.tripplanner.trip.domain;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightPoint;
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
import java.time.Instant;
import java.time.LocalDate;

/**
 * 여행의 가는 편·오는 편. 시각은 UTC Instant로 저장한다.
 * 수동 입력은 가는 편이면 도착 지점만, 오는 편이면 출발 지점만 채워진다 (반대쪽 공항·시각은 null).
 */
@Entity
@Table(name = "trip_flight")
public class TripFlight {

    static final String MANUAL_STATUS = "Manual";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FlightDirection direction;

    @Column(name = "flight_no", nullable = false, length = 8)
    private String flightNo;

    /** 출발지 현지 날짜 */
    @Column(name = "flight_date", nullable = false)
    private LocalDate flightDate;

    @Column(name = "dep_airport_iata", columnDefinition = "char(3)")
    private String depAirportIata;

    @Column(name = "arr_airport_iata", columnDefinition = "char(3)")
    private String arrAirportIata;

    @Column(name = "scheduled_dep_utc")
    private Instant scheduledDepUtc;

    @Column(name = "revised_dep_utc")
    private Instant revisedDepUtc;

    @Column(name = "scheduled_arr_utc")
    private Instant scheduledArrUtc;

    @Column(name = "revised_arr_utc")
    private Instant revisedArrUtc;

    @Column(name = "dep_terminal", length = 10)
    private String depTerminal;

    @Column(name = "arr_terminal", length = 10)
    private String arrTerminal;

    @Column(nullable = false, length = 20)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FlightSource source;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected TripFlight() {
    }

    private TripFlight(FlightDirection direction, String flightNo, LocalDate flightDate, String status,
            FlightSource source, Instant fetchedAt) {
        this.direction = direction;
        this.flightNo = flightNo;
        this.flightDate = flightDate;
        this.status = status;
        this.source = source;
        this.fetchedAt = fetchedAt;
    }

    /** 항공편 조회 결과의 한 구간으로 만든다. status는 공급자 값 그대로 (결항이면 Canceled). */
    public static TripFlight fromLeg(FlightDirection direction, String flightNo, LocalDate flightDate, FlightLeg leg,
            Instant fetchedAt) {
        TripFlight flight = new TripFlight(direction, flightNo, flightDate, leg.status(), FlightSource.AERODATABOX,
                fetchedAt);
        FlightPoint dep = leg.departure();
        FlightPoint arr = leg.arrival();
        flight.depAirportIata = dep.iata();
        flight.scheduledDepUtc = dep.scheduled();
        flight.revisedDepUtc = dep.revised();
        flight.depTerminal = dep.terminal();
        flight.arrAirportIata = arr.iata();
        flight.scheduledArrUtc = arr.scheduled();
        flight.revisedArrUtc = arr.revised();
        flight.arrTerminal = arr.terminal();
        return flight;
    }

    /** 수동 입력 가는 편: 도착 공항·시각만 안다. */
    public static TripFlight manualOutbound(String flightNo, LocalDate flightDate, String arrAirportIata,
            Instant arrivalAt, Instant fetchedAt) {
        TripFlight flight = new TripFlight(FlightDirection.OUTBOUND, flightNo, flightDate, MANUAL_STATUS,
                FlightSource.MANUAL, fetchedAt);
        flight.arrAirportIata = arrAirportIata;
        flight.scheduledArrUtc = arrivalAt;
        return flight;
    }

    /** 수동 입력 오는 편: 출발 공항·시각만 안다. */
    public static TripFlight manualReturn(String flightNo, LocalDate flightDate, String depAirportIata,
            Instant departureAt, Instant fetchedAt) {
        TripFlight flight = new TripFlight(FlightDirection.RETURN, flightNo, flightDate, MANUAL_STATUS,
                FlightSource.MANUAL, fetchedAt);
        flight.depAirportIata = depAirportIata;
        flight.scheduledDepUtc = departureAt;
        return flight;
    }

    void assignTo(Trip trip) {
        this.trip = trip;
    }

    /** 계산 기준 도착 시각: revised ?? scheduled */
    public Instant arrivalAt() {
        return revisedArrUtc != null ? revisedArrUtc : scheduledArrUtc;
    }

    /** 계산 기준 출발 시각: revised ?? scheduled */
    public Instant departureAt() {
        return revisedDepUtc != null ? revisedDepUtc : scheduledDepUtc;
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public FlightDirection getDirection() {
        return direction;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public LocalDate getFlightDate() {
        return flightDate;
    }

    public String getDepAirportIata() {
        return depAirportIata;
    }

    public String getArrAirportIata() {
        return arrAirportIata;
    }

    public Instant getScheduledDepUtc() {
        return scheduledDepUtc;
    }

    public Instant getRevisedDepUtc() {
        return revisedDepUtc;
    }

    public Instant getScheduledArrUtc() {
        return scheduledArrUtc;
    }

    public Instant getRevisedArrUtc() {
        return revisedArrUtc;
    }

    public String getDepTerminal() {
        return depTerminal;
    }

    public String getArrTerminal() {
        return arrTerminal;
    }

    public String getStatus() {
        return status;
    }

    public FlightSource getSource() {
        return source;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }
}
