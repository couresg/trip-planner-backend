package com.tripplanner.transfer.api;

import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.domain.TransportMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** 귀국일 교통편 (F3). 시각은 오는 편 출발 공항 현지 오프셋으로 표현한다. */
public record DepartureTransferResponse(
        OffsetDateTime flightDepartureAt,
        OffsetDateTime revisedDepartureAt,
        OffsetDateTime airportDeadline,
        OffsetDateTime windowStart,
        String timezone,
        int boardingMarginMin,
        boolean firstTrainTooLate,
        boolean noTimeAfterLanding,
        OffsetDateTime latestLeaveAt,
        List<Option> options) {

    public record Option(
            long routeId,
            TransportMode mode,
            String name,
            String from,
            String to,
            int durationMin,
            int walkBeforeMin,
            Fare fare,
            String boardingTip,
            boolean is24h,
            List<Departure> departures,
            OffsetDateTime leaveByAt,
            LocalDate dataAsOf,
            String source,
            String note) {
    }

    public record Departure(OffsetDateTime leaveAreaAt, OffsetDateTime departAt, OffsetDateTime arriveAirportAt,
            DepartureLabel label, boolean outsideWindow) {
    }

    public enum DepartureLabel {
        LATEST,
        SAFER
    }
}
