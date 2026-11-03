package com.tripplanner.itinerary.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 일정 설정.
 *
 * @param returnMarginMin 방문 후 숙소로 돌아오는 이동을 대신하는 여유 (분). 실측이 아닌 가정값이다.
 * @param maxItemsPerDay  한 날짜에 넣을 수 있는 항목 수
 */
@ConfigurationProperties(prefix = "app.itinerary")
public record ItineraryProperties(Integer returnMarginMin, Integer maxItemsPerDay) {

    public ItineraryProperties {
        returnMarginMin = returnMarginMin == null ? 30 : returnMarginMin;
        maxItemsPerDay = maxItemsPerDay == null ? 200 : maxItemsPerDay;
    }

    public static ItineraryProperties defaults() {
        return new ItineraryProperties(null, null);
    }
}
