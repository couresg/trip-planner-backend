package com.tripplanner.transfer.domain;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.domain.Area;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 공항 ↔ 숙소 지역 노선 (시드 데이터). 택시는 mode=TAXI, schedule_type=NONE, is_24h=true 행이다. */
@Entity
@Table(name = "transport_route")
public class TransportRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "airport_id", nullable = false)
    private Airport airport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RouteDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransportMode mode;

    @Column(name = "name_ko", nullable = false, length = 80)
    private String nameKo;

    @Column(name = "board_stop", length = 80)
    private String boardStop;

    @Column(name = "alight_stop", length = 80)
    private String alightStop;

    @Column(name = "duration_min", nullable = false)
    private int durationMin;

    @Column(name = "walk_min", nullable = false)
    private int walkMin;

    @Column(name = "fare_min")
    private Integer fareMin;

    @Column(name = "fare_max")
    private Integer fareMax;

    @Column(nullable = false, columnDefinition = "char(3)")
    private String currency;

    @Column(name = "is_24h", nullable = false)
    private boolean is24h;

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 20)
    private ScheduleType scheduleType;

    @Column(name = "first_departure_min")
    private Integer firstDepartureMin;

    @Column(name = "last_departure_min")
    private Integer lastDepartureMin;

    @Column(name = "headway_min")
    private Integer headwayMin;

    @Column(name = "boarding_tip", length = 300)
    private String boardingTip;

    @Column(length = 300)
    private String note;

    @Column(name = "source_url", nullable = false, length = 300)
    private String sourceUrl;

    @Column(name = "data_as_of", nullable = false)
    private LocalDate dataAsOf;

    @Column(nullable = false)
    private boolean active;

    @OneToMany(mappedBy = "route")
    private List<TransportTimetable> timetable = new ArrayList<>();

    protected TransportRoute() {
    }

    /** 운행 계획. TIMETABLE이면 timetable 행을 요일 유형별로 묶는다 (지연 로딩이므로 fetch 후 호출). */
    public Schedule schedule() {
        return switch (scheduleType) {
            case TIMETABLE -> Schedule.timetable(timetable.stream()
                    .collect(Collectors.groupingBy(TransportTimetable::getDayType,
                            Collectors.mapping(TransportTimetable::departure, Collectors.toList()))));
            case HEADWAY -> new Schedule(ScheduleType.HEADWAY, minute(firstDepartureMin), minute(lastDepartureMin),
                    headwayMin, Map.of());
            case NONE -> Schedule.none();
        };
    }

    private static ServiceMinute minute(Integer value) {
        return value == null ? null : ServiceMinute.of(value);
    }

    public Long getId() {
        return id;
    }

    public Airport getAirport() {
        return airport;
    }

    public Area getArea() {
        return area;
    }

    public RouteDirection getDirection() {
        return direction;
    }

    public TransportMode getMode() {
        return mode;
    }

    public String getNameKo() {
        return nameKo;
    }

    public String getBoardStop() {
        return boardStop;
    }

    public String getAlightStop() {
        return alightStop;
    }

    public int getDurationMin() {
        return durationMin;
    }

    public int getWalkMin() {
        return walkMin;
    }

    public Integer getFareMin() {
        return fareMin;
    }

    public Integer getFareMax() {
        return fareMax;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean is24h() {
        return is24h;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public Integer getFirstDepartureMin() {
        return firstDepartureMin;
    }

    public Integer getLastDepartureMin() {
        return lastDepartureMin;
    }

    public Integer getHeadwayMin() {
        return headwayMin;
    }

    public String getBoardingTip() {
        return boardingTip;
    }

    public String getNote() {
        return note;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public LocalDate getDataAsOf() {
        return dataAsOf;
    }

    public boolean isActive() {
        return active;
    }

    public List<TransportTimetable> getTimetable() {
        return timetable;
    }
}
