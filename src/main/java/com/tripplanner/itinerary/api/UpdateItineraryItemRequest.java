package com.tripplanner.itinerary.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 전체 교체다. memo는 필수(빈 문자열 허용), plannedTime은 null이거나 생략하면 지운다. */
public record UpdateItineraryItemRequest(
        @Pattern(regexp = PLANNED_TIME_PATTERN, message = PLANNED_TIME_MESSAGE) String plannedTime,
        @NotNull(message = "필수 값입니다.") @Size(max = MEMO_MAX, message = MEMO_MESSAGE) String memo) {

    static final String PLANNED_TIME_PATTERN = "^([01]\\d|2[0-3]):[0-5]\\d$";
    static final String PLANNED_TIME_MESSAGE = "시각은 HH:mm 형식입니다. 예: 18:00";
    static final int MEMO_MAX = 500;
    static final String MEMO_MESSAGE = "메모는 500자 이하입니다.";
}
