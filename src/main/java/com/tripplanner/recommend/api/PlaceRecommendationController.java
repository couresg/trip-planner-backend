package com.tripplanner.recommend.api;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.recommend.domain.Pace;
import com.tripplanner.recommend.service.PlaceRecommendationService;
import com.tripplanner.trip.api.TripController;
import java.util.EnumSet;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceRecommendationController {

    private final PlaceRecommendationService recommendationService;

    public PlaceRecommendationController(PlaceRecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    /** categories: 콤마 구분 또는 반복, 중복은 하나로 본다. 형식 검증이 여행 조회(404/403)보다 먼저다. */
    @GetMapping("/api/v1/trips/{tripId}/recommendations/places")
    public PlaceRecommendationResponse recommend(@PathVariable String tripId,
            @RequestParam List<PlaceCategory> categories,
            @RequestParam Pace pace,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        if (categories.isEmpty() || categories.contains(null)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "categories는 1개 이상이어야 합니다.");
        }
        return recommendationService.recommend(tripId, ownerToken, EnumSet.copyOf(categories), pace);
    }
}
