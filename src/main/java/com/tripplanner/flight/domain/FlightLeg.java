package com.tripplanner.flight.domain;

/** 한 편명의 한 운항 구간. status는 공급자 값을 그대로 쓴다 (예: Expected, Delayed, Canceled). */
public record FlightLeg(FlightPoint departure, FlightPoint arrival, String status) {
}
