package com.tripplanner.itinerary.api;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.itinerary.service.ItineraryService;
import com.tripplanner.transfer.service.TransferProperties;
import com.tripplanner.trip.api.TripController;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.service.FlightRefreshService;
import com.tripplanner.trip.service.FlightRefreshService.FlightRefresh;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.EnumSet;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trips/{tripId}/itinerary")
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final TransferProperties properties;
    private final FlightRefreshService flightRefreshService;

    public ItineraryController(ItineraryService itineraryService, TransferProperties properties,
            FlightRefreshService flightRefreshService) {
        this.itineraryService = itineraryService;
        this.properties = properties;
        this.flightRefreshService = flightRefreshService;
    }

    /**
     * immigrationMin·airportBufferMin: 교통편 API와 같은 기본값·허용 값. 허용 값 검증이 여행 조회(404/403)보다 먼저다.
     * 계산 전에 두 편을 재조회한다 (실패해도 저장된 값으로 응답).
     */
    @GetMapping
    public ItineraryResponse get(@PathVariable String tripId,
            @RequestParam(required = false) Integer immigrationMin,
            @RequestParam(required = false) Integer airportBufferMin,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        int immigration = immigrationMin == null ? properties.defaultImmigrationMin() : immigrationMin;
        if (!properties.immigrationOptions().contains(immigration)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "immigrationMin은 " + properties.immigrationOptions() + " 중 하나여야 합니다.");
        }
        int buffer = airportBufferMin == null ? properties.defaultAirportBufferMin() : airportBufferMin;
        if (!properties.airportBufferOptions().contains(buffer)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "airportBufferMin은 " + properties.airportBufferOptions() + " 중 하나여야 합니다.");
        }
        FlightRefresh refresh = flightRefreshService.refresh(tripId, ownerToken,
                EnumSet.of(FlightDirection.OUTBOUND, FlightDirection.RETURN));
        return itineraryService.get(tripId, ownerToken, immigration, buffer, refresh);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItineraryItemResponse addItem(@PathVariable String tripId,
            @Valid @RequestBody AddItineraryItemRequest request,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        return itineraryService.addItem(tripId, ownerToken, request);
    }

    @PatchMapping("/items/{itemId}")
    public ItineraryItemResponse updateItem(@PathVariable String tripId, @PathVariable long itemId,
            @Valid @RequestBody UpdateItineraryItemRequest request,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        return itineraryService.updateItem(tripId, ownerToken, itemId, request);
    }

    @PutMapping("/days/{date}/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@PathVariable String tripId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody ReorderItineraryDayRequest request,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        itineraryService.reorder(tripId, ownerToken, date, request.itemIds());
    }

    @DeleteMapping("/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(@PathVariable String tripId, @PathVariable long itemId,
            @RequestHeader(value = TripController.OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        itineraryService.deleteItem(tripId, ownerToken, itemId);
    }
}
