package com.tripplanner.trip.api;

import com.tripplanner.trip.service.TripService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/trips")
public class TripController {

    public static final String OWNER_TOKEN_HEADER = "X-Owner-Token";

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    public ResponseEntity<CreateTripResponse> create(@Valid @RequestBody CreateTripRequest request) {
        CreateTripResponse response = tripService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{tripId}")
                .buildAndExpand(response.tripId())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    /** 헤더: 콤마로 구분한 소유 토큰 목록 (최대 50개). 없으면 빈 목록. */
    @GetMapping
    public List<TripSummaryResponse> list(
            @RequestHeader(value = OWNER_TOKEN_HEADER, required = false) String ownerTokens) {
        return tripService.list(ownerTokens);
    }

    /** tripId 형식 오류도 TRIP_NOT_FOUND로 응답하도록 문자열로 받는다. */
    @GetMapping("/{tripId}")
    public TripDetailResponse get(@PathVariable String tripId,
            @RequestHeader(value = OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        return tripService.get(tripId, ownerToken);
    }

    @DeleteMapping("/{tripId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String tripId,
            @RequestHeader(value = OWNER_TOKEN_HEADER, required = false) String ownerToken) {
        tripService.delete(tripId, ownerToken);
    }
}
