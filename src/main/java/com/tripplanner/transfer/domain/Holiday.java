package com.tripplanner.transfer.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** 나라별 공휴일 한 날 (대체 휴일·국민의 휴일 포함). 시드로만 넣는다. */
@Entity
@Table(name = "holiday")
public class Holiday {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "country_code", nullable = false, columnDefinition = "char(2)")
    private String countryCode;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = 100)
    private String name;

    protected Holiday() {
    }

    public Holiday(String countryCode, LocalDate holidayDate, String name) {
        this.countryCode = countryCode;
        this.holidayDate = holidayDate;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public String getName() {
        return name;
    }
}
