package com.tripplanner.catalog.api;

import com.tripplanner.catalog.service.CatalogService;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cities")
public class CatalogController {

    /** 도시 코드 형식. 장소 API 등 도시 하위 경로에서도 같은 규칙을 쓴다. */
    public static final String CITY_CODE_PATTERN = "^[A-Z]{3}$";
    public static final String CITY_CODE_MESSAGE = "도시 코드는 영문 대문자 3자리입니다.";

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public List<CityResponse> cities() {
        return catalogService.getCities();
    }

    @GetMapping("/{cityCode}/areas")
    public List<AreaResponse> areas(
            @PathVariable @Pattern(regexp = CITY_CODE_PATTERN, message = CITY_CODE_MESSAGE) String cityCode) {
        return catalogService.getAreas(cityCode);
    }
}
