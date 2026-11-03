package com.tripplanner.itinerary.service;

import com.tripplanner.itinerary.domain.ItineraryWarning;
import com.tripplanner.place.api.PlaceResponse.OpeningHour;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 일정 항목 경고 계산 (F4). 방문 시각은 visit_date + planned_time을 도시 timezone에 놓은 instant다.
 * BEFORE_ARRIVAL: 시각이 있으면 방문 &lt; earliest, 없으면 방문일 &lt; earliest의 현지 날짜.
 * AFTER_DEPARTURE_LEAVE_TIME: 시각이 있으면 방문 + 체류 시간 + 숙소 복귀 여유 &gt; latest, 없으면 방문일 &gt; latest의 현지 날짜. latest가 없으면 경고 없음.
 * OUTSIDE_OPENING_HOURS: 시각이 있을 때만 본다. 상시 개방·빈 목록(정보 없음)은 경고 없음, 해당 요일 항목이 없으면 휴무,
 * open ≤ t &lt; close면 영업 중. close &lt; open은 자정 넘김이라 open~자정이고 다음 날 00:00~close도 그 항목이 덮는다.
 * 시작이 영업 중이어도 시작 + 체류 시간이 그 구간의 close를 넘으면 경고한다 (같으면 경고 없음).
 * 요일은 방문일 기준이고 공휴일은 고려하지 않는다.
 */
@Component
public class ItineraryWarningCalculator {

    private static final Logger log = LoggerFactory.getLogger(ItineraryWarningCalculator.class);
    private static final TypeReference<List<OpeningHour>> OPENING_HOURS_TYPE = new TypeReference<>() {
    };
    /** MON~SUN */
    private static final Map<String, DayOfWeek> DAYS = Arrays.stream(DayOfWeek.values())
            .collect(Collectors.toMap(day -> day.name().substring(0, 3), Function.identity()));

    private final ObjectMapper objectMapper;
    private final ItineraryProperties properties;

    public ItineraryWarningCalculator(ObjectMapper objectMapper, ItineraryProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * @param earliest 숙소 도착 예상 시각
     * @param latest   숙소 출발 마감 시각, 없으면 null
     * @param zone     방문일·방문 시각을 해석할 도시 timezone
     */
    public List<ItineraryWarning> calculate(Visit visit, Instant earliest, Instant latest, ZoneId zone) {
        List<ItineraryWarning> warnings = new ArrayList<>();
        if (outsideOpeningHours(visit)) {
            warnings.add(ItineraryWarning.OUTSIDE_OPENING_HOURS);
        }
        if (beforeArrival(visit, earliest, zone)) {
            warnings.add(ItineraryWarning.BEFORE_ARRIVAL);
        }
        if (latest != null && afterDepartureLeaveTime(visit, latest, zone)) {
            warnings.add(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
        }
        return warnings;
    }

    private static boolean beforeArrival(Visit visit, Instant earliest, ZoneId zone) {
        return visit.plannedTime() == null
                ? visit.date().isBefore(earliest.atZone(zone).toLocalDate())
                : visitAt(visit, zone).isBefore(earliest);
    }

    private boolean afterDepartureLeaveTime(Visit visit, Instant latest, ZoneId zone) {
        return visit.plannedTime() == null
                ? visit.date().isAfter(latest.atZone(zone).toLocalDate())
                : visitAt(visit, zone).plusSeconds((visit.durationMin() + properties.returnMarginMin()) * 60L)
                        .isAfter(latest);
    }

    private static Instant visitAt(Visit visit, ZoneId zone) {
        return visit.date().atTime(visit.plannedTime()).atZone(zone).toInstant();
    }

    private boolean outsideOpeningHours(Visit visit) {
        if (visit.plannedTime() == null || visit.alwaysOpen()) {
            return false;
        }
        List<Hours> hours;
        try {
            hours = parse(visit.openingHoursJson());
        } catch (JacksonException | DateTimeParseException | IllegalArgumentException e) {
            log.warn("장소 {}의 영업시간을 해석할 수 없어 영업시간 경고를 건너뜁니다.", visit.placeId(), e);
            return false;
        }
        if (hours.isEmpty()) {
            return false;
        }
        // 영업시간은 벽시계 값이라 현지 날짜·시각 그대로 비교한다. 구간이 겹치면 가장 늦게 닫는 구간을 쓴다.
        LocalDateTime start = visit.date().atTime(visit.plannedTime());
        return hours.stream()
                .map(h -> h.closeOfIntervalContaining(start))
                .flatMap(Optional::stream)
                .max(Comparator.naturalOrder())
                .map(close -> start.plusMinutes(visit.durationMin()).isAfter(close))
                .orElse(true);
    }

    private List<Hours> parse(String json) {
        List<OpeningHour> entries = objectMapper.readValue(json, OPENING_HOURS_TYPE);
        if (entries == null) {
            throw new IllegalArgumentException("영업시간이 null입니다.");
        }
        return entries.stream().map(ItineraryWarningCalculator::toHours).toList();
    }

    private static Hours toHours(OpeningHour entry) {
        if (entry == null || !DAYS.containsKey(entry.dayOfWeek()) || entry.open() == null || entry.close() == null) {
            throw new IllegalArgumentException("영업시간 항목 형식 오류");
        }
        return new Hours(DAYS.get(entry.dayOfWeek()), LocalTime.parse(entry.open()), LocalTime.parse(entry.close()));
    }

    /** 경고 계산에 필요한 항목·장소 값 */
    public record Visit(long placeId, LocalDate date, LocalTime plannedTime, int durationMin, boolean alwaysOpen,
            String openingHoursJson) {
    }

    private record Hours(DayOfWeek day, LocalTime open, LocalTime close) {

        /** start가 이 항목의 영업 구간 안이면 그 구간이 닫는 시각 */
        Optional<LocalDateTime> closeOfIntervalContaining(LocalDateTime start) {
            LocalDate date = start.toLocalDate();
            LocalTime time = start.toLocalTime();
            boolean overnight = close.isBefore(open);
            if (date.getDayOfWeek() == day && !time.isBefore(open) && (overnight || time.isBefore(close))) {
                return Optional.of((overnight ? date.plusDays(1) : date).atTime(close));
            }
            if (overnight && date.getDayOfWeek().minus(1) == day && time.isBefore(close)) {
                return Optional.of(date.atTime(close));
            }
            return Optional.empty();
        }
    }
}
