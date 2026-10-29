package com.tripplanner.flight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 항공편 조회 결과 캐시. response_json은 공급자 원본이 아니라 정규화한 List&lt;FlightLeg&gt; JSON이다. */
@Entity
@Table(name = "flight_cache")
public class FlightCacheEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_no", nullable = false, length = 8)
    private String flightNo;

    /** 출발지 현지 날짜 */
    @Column(name = "flight_date", nullable = false)
    private LocalDate flightDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", nullable = false)
    private String responseJson;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected FlightCacheEntry() {
    }

    public FlightCacheEntry(String flightNo, LocalDate flightDate, String responseJson, Instant fetchedAt,
            Instant expiresAt) {
        this.flightNo = flightNo;
        this.flightDate = flightDate;
        refresh(responseJson, fetchedAt, expiresAt);
    }

    public void refresh(String responseJson, Instant fetchedAt, Instant expiresAt) {
        this.responseJson = responseJson;
        this.fetchedAt = fetchedAt;
        this.expiresAt = expiresAt;
    }

    public boolean isFresh(Instant now) {
        return now.isBefore(expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getFlightNo() {
        return flightNo;
    }

    public LocalDate getFlightDate() {
        return flightDate;
    }

    public String getResponseJson() {
        return responseJson;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
