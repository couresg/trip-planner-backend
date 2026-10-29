package com.tripplanner.trip.api;

import com.tripplanner.catalog.api.CatalogController;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/** 편명은 형식 검증(INVALID_FLIGHT_NO)을 FlightNumber.of에 맡기므로 여기서 제약을 두지 않는다. */
public record CreateTripRequest(
        String outboundFlightNo,
        String returnFlightNo,
        @NotNull(message = "필수 값입니다.")
        @Pattern(regexp = CatalogController.CITY_CODE_PATTERN, message = CatalogController.CITY_CODE_MESSAGE)
        String cityCode,
        @NotNull(message = "필수 값입니다.") Long areaId,
        @NotNull(message = "필수 값입니다.") LocalDate startDate,
        @NotNull(message = "필수 값입니다.") LocalDate endDate,
        @Valid ManualOverride manualOverride) {
}
