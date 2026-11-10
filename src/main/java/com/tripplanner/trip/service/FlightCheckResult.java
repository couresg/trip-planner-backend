package com.tripplanner.trip.service;

/** 교통편·일정 조회 전 저장된 항공편 재조회 결과. */
public enum FlightCheckResult {
    /** 조회해서 저장된 값을 갱신했다 (값이 같아도). */
    REFRESHED,
    /** 찾지 못함·공급자 장애·공항이 다른 구간뿐이라 저장된 값을 쓴다. */
    FAILED,
    /** 재조회 구간 밖이라 조회하지 않았다. */
    OUT_OF_WINDOW,
    /** 수동 입력 편이라 조회하지 않았다. */
    MANUAL
}
