package com.tripplanner.trip.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tripplanner.trip.domain.FlightSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** 시각은 해당 공항의 현지 오프셋으로 내려준다 (revised ?? scheduled). */
public record TripDetailResponse(
        String tripId,
        CityInfo city,
        AreaInfo area,
        LocalDate startDate,
        LocalDate endDate,
        Outbound outbound,
        @JsonProperty("return") Return returnFlight) {

    public record CityInfo(String code, String nameKo, String timezone) {
    }

    public record AreaInfo(Long id, String nameKo, double lat, double lng) {
    }

    public record Outbound(String flightNo, String arrivalAirport, OffsetDateTime arrivalAt, FlightSource source,
            String status) {
    }

    public record Return(String flightNo, String departureAirport, OffsetDateTime departureAt, FlightSource source,
            String status) {
    }
}
