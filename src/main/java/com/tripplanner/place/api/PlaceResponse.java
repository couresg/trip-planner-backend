package com.tripplanner.place.api;

import com.tripplanner.place.domain.PlaceCategory;
import java.util.List;

public record PlaceResponse(
        Long id,
        String nameKo,
        PlaceCategory category,
        double lat,
        double lng,
        List<OpeningHour> openingHours,
        boolean alwaysOpen,
        int durationMin,
        String summary) {

    /** dayOfWeek: MON~SUN, open/close: 현지 시각 HH:mm */
    public record OpeningHour(String dayOfWeek, String open, String close) {
    }
}
