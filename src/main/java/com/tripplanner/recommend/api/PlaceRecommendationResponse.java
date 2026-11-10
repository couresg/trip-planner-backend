package com.tripplanner.recommend.api;

import com.tripplanner.place.api.PlaceResponse;
import com.tripplanner.recommend.domain.Pace;
import java.util.List;

/** candidateCount: 일정에 없는 도시 장소 수. recommendations는 rank 1부터 빈틈없이. */
public record PlaceRecommendationResponse(
        Pace pace,
        int placesPerDay,
        int dayCount,
        int candidateCount,
        List<Recommendation> recommendations) {

    public record Recommendation(int rank, String reason, PlaceResponse place) {
    }
}
