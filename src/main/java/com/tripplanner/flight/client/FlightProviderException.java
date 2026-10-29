package com.tripplanner.flight.client;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;

/**
 * 항공편 공급자 장애(한도 초과, 5xx, 네트워크 오류, 응답 해석 실패).
 * 처리되지 않으면 FLIGHT_PROVIDER_UNAVAILABLE(503)로 응답하며, 원인은 로그에만 남긴다.
 */
public class FlightProviderException extends BusinessException {

    private final String reason;

    public FlightProviderException(String reason, Throwable cause) {
        super(ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE, ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE.title());
        this.reason = reason;
        if (cause != null) {
            initCause(cause);
        }
    }

    public String reason() {
        return reason;
    }
}
