package com.tripplanner.transfer.domain;

public enum TransportMode {
    RAIL,
    SUBWAY,
    BUS,
    TAXI,
    RIDE_HAILING;

    /** 시간표 없이 바로 탈 수 있는 수단 (택시·차량 호출). 대중교통 판정에서 빠진다. */
    public boolean isOnDemand() {
        return this == TAXI || this == RIDE_HAILING;
    }
}
