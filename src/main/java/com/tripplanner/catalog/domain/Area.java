package com.tripplanner.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** 숙소 지역. 대표 좌표는 지역의 주요 역이다. */
@Entity
@Table(name = "area")
public class Area {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @Column(name = "name_ko", nullable = false, length = 50)
    private String nameKo;

    @Column(length = 200)
    private String description;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected Area() {
    }

    public Long getId() {
        return id;
    }

    public City getCity() {
        return city;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
