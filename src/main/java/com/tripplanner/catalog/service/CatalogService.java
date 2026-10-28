package com.tripplanner.catalog.service;

import com.tripplanner.catalog.api.AreaResponse;
import com.tripplanner.catalog.api.CityResponse;
import com.tripplanner.catalog.api.CityResponse.AirportSummary;
import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.domain.City;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.catalog.repository.AreaRepository;
import com.tripplanner.catalog.repository.CityRepository;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final CityRepository cityRepository;
    private final AirportRepository airportRepository;
    private final AreaRepository areaRepository;

    public CatalogService(CityRepository cityRepository, AirportRepository airportRepository,
            AreaRepository areaRepository) {
        this.cityRepository = cityRepository;
        this.airportRepository = airportRepository;
        this.areaRepository = areaRepository;
    }

    public List<CityResponse> getCities() {
        List<City> cities = cityRepository.findAllByActiveTrueOrderByIdAsc();
        if (cities.isEmpty()) {
            return List.of();
        }
        Map<Long, List<AirportSummary>> airportsByCityId = airportRepository
                .findAllByCityIdInOrderByIataAsc(cities.stream().map(City::getId).toList()).stream()
                .collect(Collectors.groupingBy(a -> a.getCity().getId(),
                        Collectors.mapping(CatalogService::toSummary, Collectors.toList())));
        return cities.stream()
                .map(c -> new CityResponse(c.getCode(), c.getNameKo(), c.getCountryCode(), c.getTimezone(),
                        airportsByCityId.getOrDefault(c.getId(), List.of())))
                .toList();
    }

    public List<AreaResponse> getAreas(String cityCode) {
        City city = getActiveCity(cityCode);
        return areaRepository.findAllByCityIdOrderBySortOrderAscIdAsc(city.getId()).stream()
                .map(a -> new AreaResponse(a.getId(), a.getNameKo(), a.getLat().doubleValue(),
                        a.getLng().doubleValue(), a.getDescription()))
                .toList();
    }

    public City getActiveCity(String cityCode) {
        return cityRepository.findByCodeAndActiveTrue(cityCode)
                .orElseThrow(() -> new BusinessException(ErrorCode.CITY_NOT_FOUND,
                        "지원하지 않는 도시입니다: " + cityCode));
    }

    private static AirportSummary toSummary(Airport airport) {
        return new AirportSummary(airport.getIata(), airport.getNameKo());
    }
}
