package com.tripplanner.itinerary.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** plannedTime·memo는 생략할 수 있다 (memo 생략은 빈 문자열). */
public record AddItineraryItemRequest(
        @NotNull(message = "필수 값입니다.") Long placeId,
        @NotNull(message = "필수 값입니다.") LocalDate date,
        @Pattern(regexp = UpdateItineraryItemRequest.PLANNED_TIME_PATTERN,
                message = UpdateItineraryItemRequest.PLANNED_TIME_MESSAGE)
        String plannedTime,
        @Size(max = UpdateItineraryItemRequest.MEMO_MAX, message = UpdateItineraryItemRequest.MEMO_MESSAGE)
        String memo) {
}
