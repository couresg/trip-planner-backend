package com.tripplanner.trip.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;

/**
 * 항공편 조회 실패 시 수동 입력. 방향별로 (시각 + 공항) 쌍을 함께 입력하거나 둘 다 비운다.
 * 비운 방향은 항공편 조회로 채운다.
 */
public record ManualOverride(
        OffsetDateTime outboundArrivalAt,
        @Pattern(regexp = AIRPORT_PATTERN, message = AIRPORT_MESSAGE) String outboundArrivalAirport,
        OffsetDateTime returnDepartureAt,
        @Pattern(regexp = AIRPORT_PATTERN, message = AIRPORT_MESSAGE) String returnDepartureAirport) {

    static final String AIRPORT_PATTERN = "^[A-Za-z]{3}$";
    static final String AIRPORT_MESSAGE = "공항 코드는 영문 3자리입니다. 예: KIX";

    public boolean hasOutbound() {
        return outboundArrivalAt != null && !isBlank(outboundArrivalAirport);
    }

    public boolean hasReturn() {
        return returnDepartureAt != null && !isBlank(returnDepartureAirport);
    }

    @JsonIgnore
    @AssertTrue(message = "가는 편 도착 시각과 도착 공항은 함께 입력해야 합니다.")
    public boolean isOutboundPairComplete() {
        return (outboundArrivalAt == null) == isBlank(outboundArrivalAirport);
    }

    @JsonIgnore
    @AssertTrue(message = "오는 편 출발 시각과 출발 공항은 함께 입력해야 합니다.")
    public boolean isReturnPairComplete() {
        return (returnDepartureAt == null) == isBlank(returnDepartureAirport);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
