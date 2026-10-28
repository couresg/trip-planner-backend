package com.tripplanner.place.api;

import static com.tripplanner.catalog.api.CatalogController.CITY_CODE_MESSAGE;
import static com.tripplanner.catalog.api.CatalogController.CITY_CODE_PATTERN;

import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.service.PlaceService;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping("/api/v1/cities/{cityCode}/places")
    public List<PlaceResponse> places(
            @PathVariable @Pattern(regexp = CITY_CODE_PATTERN, message = CITY_CODE_MESSAGE) String cityCode,
            @RequestParam(required = false) PlaceCategory category,
            @RequestParam(required = false) @Size(max = 50, message = "검색어는 50자 이하입니다.") String q) {
        return placeService.getPlaces(cityCode, category, q);
    }
}
