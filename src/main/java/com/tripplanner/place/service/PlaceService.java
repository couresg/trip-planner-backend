package com.tripplanner.place.service;

import com.tripplanner.catalog.domain.City;
import com.tripplanner.catalog.service.CatalogService;
import com.tripplanner.place.api.PlaceResponse;
import com.tripplanner.place.api.PlaceResponse.OpeningHour;
import com.tripplanner.place.domain.Place;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.repository.PlaceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
public class PlaceService {

    private static final TypeReference<List<OpeningHour>> OPENING_HOURS_TYPE = new TypeReference<>() {
    };

    private final CatalogService catalogService;
    private final PlaceRepository placeRepository;
    private final ObjectMapper objectMapper;

    public PlaceService(CatalogService catalogService, PlaceRepository placeRepository, ObjectMapper objectMapper) {
        this.catalogService = catalogService;
        this.placeRepository = placeRepository;
        this.objectMapper = objectMapper;
    }

    /** category·q가 null이면 해당 조건 없이 조회한다. q는 장소 이름 부분 일치. */
    public List<PlaceResponse> getPlaces(String cityCode, PlaceCategory category, String q) {
        City city = catalogService.getActiveCity(cityCode);
        String namePattern = (q == null || q.isBlank()) ? null : "%" + escapeLike(q.strip()) + "%";
        return placeRepository.search(city.getId(), category, namePattern).stream()
                .map(this::toResponse)
                .toList();
    }

    private PlaceResponse toResponse(Place p) {
        return new PlaceResponse(p.getId(), p.getNameKo(), p.getCategory(), p.getLat().doubleValue(),
                p.getLng().doubleValue(), objectMapper.readValue(p.getOpeningHoursJson(), OPENING_HOURS_TYPE),
                p.isAlwaysOpen(), p.getDurationMin(), p.getSummary());
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
