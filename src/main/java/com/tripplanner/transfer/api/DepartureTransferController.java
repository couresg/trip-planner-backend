package com.tripplanner.transfer.api;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.transfer.service.DepartureTransferService;
import com.tripplanner.transfer.service.TransferProperties;
import com.tripplanner.trip.api.TripController;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.service.FlightRefreshService;
import com.tripplanner.trip.service.FlightRefreshService.FlightRefresh;
import java.util.EnumSet;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/transfers")
public class DepartureTransferController {

    private final DepartureTransferService departureTransferService;
    private final TransferProperties properties;
    private final FlightRefreshService flightRefreshService;

    public DepartureTransferController(DepartureTransferService departureTransferService,
            TransferProperties properties, FlightRefreshService flightRefreshService) {
        this.departureTransferService = departureTransferService;
        this.properties = properties;
        this.flightRefreshService = flightRefreshService;
    }

    /** airportBufferMin: 공항 도착 여유 (기본 120, 허용 120/150/180). 허용 값 검증이 여행 조회(404/403)보다 먼저다.
     * 계산 전에 두 편을 재조회한다 (실패해도 저장된 값으로 응답). */
    @GetMapping("/departure")
    public DepartureTransferResponse departure(@PathVariable String tripId,
            @RequestParam(required = false) Integer airportBufferMin,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        int buffer = airportBufferMin == null ? properties.defaultAirportBufferMin() : airportBufferMin;
        if (!properties.airportBufferOptions().contains(buffer)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "airportBufferMin은 " + properties.airportBufferOptions() + " 중 하나여야 합니다.");
        }
        // 착륙 전 제외가 가는 편 도착을 쓰므로 두 편 모두 재조회한다.
        FlightRefresh refresh = flightRefreshService.refresh(tripId, ownerToken,
                EnumSet.of(FlightDirection.OUTBOUND, FlightDirection.RETURN));
        return departureTransferService.departure(tripId, ownerToken, buffer, refresh.of(FlightDirection.RETURN));
    }
}
