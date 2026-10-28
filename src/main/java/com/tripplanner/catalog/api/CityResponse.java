package com.tripplanner.catalog.api;

import java.util.List;

public record CityResponse(
        String code,
        String nameKo,
        String country,
        String timezone,
        List<AirportSummary> airports) {

    public record AirportSummary(String iata, String nameKo) {
    }
}
