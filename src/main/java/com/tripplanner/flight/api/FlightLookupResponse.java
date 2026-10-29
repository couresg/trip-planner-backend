package com.tripplanner.flight.api;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.domain.FlightPoint;
import java.time.OffsetDateTime;
import java.util.List;

/** 시각은 해당 공항의 현지 오프셋으로 내려준다. */
public record FlightLookupResponse(String flightNo, List<Leg> legs) {

    public static FlightLookupResponse of(FlightNumber flightNumber, List<FlightLeg> legs) {
        return new FlightLookupResponse(flightNumber.value(), legs.stream().map(Leg::from).toList());
    }

    public record Leg(Point departure, Point arrival, String status) {

        static Leg from(FlightLeg leg) {
            return new Leg(Point.from(leg.departure()), Point.from(leg.arrival()), leg.status());
        }
    }

    public record Point(String iata, OffsetDateTime scheduled, OffsetDateTime revised, String terminal) {

        static Point from(FlightPoint point) {
            return new Point(point.iata(), point.scheduledLocal(), point.revisedLocal(), point.terminal());
        }
    }
}
