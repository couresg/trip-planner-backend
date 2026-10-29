package com.tripplanner.flight.client;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import java.time.LocalDate;
import java.util.List;

/** 항공편 공급자. external.mock 값에 따라 Mock 또는 AeroDataBox 구현이 등록된다. */
public interface FlightClient {

    /**
     * @param departureLocalDate 출발지 현지 날짜
     * @return 해당 편명의 구간 목록. 찾지 못하면 빈 목록
     * @throws FlightProviderException 공급자 장애·한도 초과·네트워크 오류
     */
    List<FlightLeg> findLegs(FlightNumber flightNumber, LocalDate departureLocalDate);
}
