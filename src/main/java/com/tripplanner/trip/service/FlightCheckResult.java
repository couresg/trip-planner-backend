package com.tripplanner.trip.service;

/** 교통편·일정 조회 전 저장된 항공편 재조회 결과. */
public enum FlightCheckResult {
    /** 조회에 성공했다 (더 나중에 받은 값이면 저장된 값을 갱신, 같은 값이면 그대로). */
    REFRESHED,
    /** 찾지 못함·공급자 장애(만료된 캐시 대체 포함)·공항이 다른 구간뿐이라 저장된 값을 쓴다. */
    FAILED,
    /** 재조회 구간(예정 출발 48시간 전) 전이라 조회하지 않았다. */
    BEFORE_WINDOW,
    /** 재조회 구간이 지나(가는 편 도착 3시간 뒤, 오는 편 출발 뒤) 조회하지 않았다. */
    AFTER_WINDOW,
    /** 수동 입력 편이라 조회하지 않았다. */
    MANUAL
}
