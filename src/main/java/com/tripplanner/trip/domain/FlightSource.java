package com.tripplanner.trip.domain;

/** 여행 항공편 정보의 출처. MANUAL은 항공편 조회 실패 시 사용자가 직접 입력한 값이다. */
public enum FlightSource {
    AERODATABOX,
    MANUAL
}
