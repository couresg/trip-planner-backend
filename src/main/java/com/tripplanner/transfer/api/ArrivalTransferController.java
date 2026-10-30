package com.tripplanner.transfer.api;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.transfer.service.ArrivalTransferService;
import com.tripplanner.transfer.service.TransferProperties;
import com.tripplanner.trip.api.TripController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/transfers")
public class ArrivalTransferController {

    private final ArrivalTransferService arrivalTransferService;
    private final TransferProperties properties;

    public ArrivalTransferController(ArrivalTransferService arrivalTransferService, TransferProperties properties) {
        this.arrivalTransferService = arrivalTransferService;
        this.properties = properties;
    }

    /** immigrationMin: 입국 소요 시간 (기본 60, 허용 30/60/90). 허용 값 검증이 여행 조회(404/403)보다 먼저다. */
    @GetMapping("/arrival")
    public ArrivalTransferResponse arrival(@PathVariable String tripId,
            @RequestParam(required = false) Integer immigrationMin,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        int immigration = immigrationMin == null ? properties.defaultImmigrationMin() : immigrationMin;
        if (!properties.immigrationOptions().contains(immigration)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "immigrationMin은 " + properties.immigrationOptions() + " 중 하나여야 합니다.");
        }
        return arrivalTransferService.arrival(tripId, ownerToken, immigration);
    }
}
