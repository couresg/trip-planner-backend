package com.tripplanner.flight.domain;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 한 구간의 출발 또는 도착 지점. 시각은 UTC Instant, 표시는 공항 timezone 기준. revised·terminal은 없을 수 있다. */
public record FlightPoint(String iata, ZoneId timezone, Instant scheduled, Instant revised, String terminal) {

    /** 계산 기준 시각: revised가 있으면 revised, 없으면 scheduled. */
    public Instant effectiveTime() {
        return revised != null ? revised : scheduled;
    }

    public OffsetDateTime scheduledLocal() {
        return scheduled.atZone(timezone).toOffsetDateTime();
    }

    public OffsetDateTime revisedLocal() {
        return revised == null ? null : revised.atZone(timezone).toOffsetDateTime();
    }
}
