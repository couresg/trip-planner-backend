package com.tripplanner.common.exception;

import org.springframework.http.HttpStatus;

/** docs/API_SPEC.md 7장 공통 오류 코드와 일치시킨다. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력 형식 오류"),
    INVALID_FLIGHT_NO(HttpStatus.BAD_REQUEST, "편명 형식 오류"),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "날짜 범위 오류"),
    CITY_AIRPORT_MISMATCH(HttpStatus.BAD_REQUEST, "도시와 공항 불일치"),
    DATE_OUT_OF_TRIP(HttpStatus.BAD_REQUEST, "여행 기간 밖 날짜"),
    FORBIDDEN_TRIP(HttpStatus.FORBIDDEN, "소유 토큰 불일치"),
    TRIP_NOT_FOUND(HttpStatus.NOT_FOUND, "여행을 찾을 수 없음"),
    FLIGHT_NOT_FOUND(HttpStatus.NOT_FOUND, "항공편을 찾을 수 없음"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청 한도 초과"),
    FLIGHT_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "외부 항공 API 장애"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
