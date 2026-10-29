package com.tripplanner.trip.api;

/** ownerToken 원문은 이 응답에서 한 번만 내려주고 서버에는 해시만 남는다. */
public record CreateTripResponse(String tripId, String ownerToken) {
}
