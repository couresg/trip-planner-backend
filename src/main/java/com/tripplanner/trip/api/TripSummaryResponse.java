package com.tripplanner.trip.api;

import java.time.LocalDate;

public record TripSummaryResponse(
        String tripId,
        CityRef city,
        AreaRef area,
        LocalDate startDate,
        LocalDate endDate,
        String outboundFlightNo,
        String returnFlightNo) {

    public record CityRef(String code, String nameKo) {
    }

    public record AreaRef(Long id, String nameKo) {
    }
}
