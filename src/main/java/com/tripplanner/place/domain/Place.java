package com.tripplanner.place.domain;

import com.tripplanner.catalog.domain.City;
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
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "place")
public class Place {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @Column(name = "google_place_id", unique = true, length = 100)
    private String googlePlaceId;

    @Column(name = "name_ko", nullable = false, length = 100)
    private String nameKo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlaceCategory category;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(length = 300)
    private String summary;

    @Column(name = "duration_min", nullable = false)
    private int durationMin;

    /** [{dayOfWeek, open, close}] 형식의 JSON 원문. 상시 개방은 [] + alwaysOpen. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "opening_hours_json", nullable = false)
    private String openingHoursJson;

    @Column(name = "always_open", nullable = false)
    private boolean alwaysOpen;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PlaceSource source;

    protected Place() {
    }

    public Long getId() {
        return id;
    }

    public City getCity() {
        return city;
    }

    public String getGooglePlaceId() {
        return googlePlaceId;
    }

    public String getNameKo() {
        return nameKo;
    }

    public PlaceCategory getCategory() {
        return category;
    }

    public BigDecimal getLat() {
        return lat;
    }

    public BigDecimal getLng() {
        return lng;
    }

    public String getSummary() {
        return summary;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public String getOpeningHoursJson() {
        return openingHoursJson;
    }

    public boolean isAlwaysOpen() {
        return alwaysOpen;
    }

    public PlaceSource getSource() {
        return source;
    }
}
