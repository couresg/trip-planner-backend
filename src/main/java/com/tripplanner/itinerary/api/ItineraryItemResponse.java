package com.tripplanner.itinerary.api;

import java.time.LocalDate;

/** 항목 추가·수정 응답. 경고는 담지 않으며 클라이언트가 GET으로 다시 조회한다. */
public record ItineraryItemResponse(long itemId, LocalDate date, int position, String plannedTime, String memo,
        ItineraryResponse.Place place) {
}
