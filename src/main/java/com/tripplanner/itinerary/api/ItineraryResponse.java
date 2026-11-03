package com.tripplanner.itinerary.api;

import com.tripplanner.itinerary.domain.ItineraryWarning;
import com.tripplanner.place.domain.PlaceCategory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 여행 시작일~종료일의 일자별 일정 (항목이 없는 날 포함).
 *
 * @param returnMarginMin AFTER_DEPARTURE_LEAVE_TIME 판정에 더한 숙소 복귀 여유 (분)
 */
public record ItineraryResponse(String timezone, int returnMarginMin, List<Day> days) {

    /**
     * @param earliestStart 숙소 도착 예상 시각, 그 시각이 속한 날에만 채운다
     * @param latestEnd     숙소 출발 마감 시각, 그 시각이 속한 날에만 채운다
     */
    public record Day(LocalDate date, int dayIndex, OffsetDateTime earliestStart, OffsetDateTime latestEnd,
            List<Item> items) {
    }

    /** plannedTime: 현지 시각 HH:mm 또는 null */
    public record Item(long itemId, int position, String plannedTime, String memo, Place place,
            List<ItineraryWarning> warnings) {
    }

    public record Place(long id, String nameKo, PlaceCategory category, double lat, double lng, int durationMin) {
    }
}
