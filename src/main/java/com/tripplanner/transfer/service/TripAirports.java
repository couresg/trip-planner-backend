package com.tripplanner.transfer.service;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.trip.domain.Trip;
import java.util.List;

/** 여행 도시의 공항 조회. 여행 생성 시 항공편 공항이 도시 공항임을 검증했으므로 없으면 데이터 오류다. */
final class TripAirports {

    private TripAirports() {
    }

    static Airport find(AirportRepository airportRepository, Trip trip, String iata) {
        return airportRepository.findAllByCityIdInOrderByIataAsc(List.of(trip.getCity().getId())).stream()
                .filter(a -> a.getIata().equals(iata))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("여행 도시에 항공편 공항이 없음: " + iata));
    }
}
