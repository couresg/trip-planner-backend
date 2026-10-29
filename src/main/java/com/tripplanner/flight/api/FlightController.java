package com.tripplanner.flight.api;

import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.service.FlightService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flights")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    /** date는 출발지 현지 날짜 (YYYY-MM-DD). */
    @GetMapping("/lookup")
    public FlightLookupResponse lookup(
            @RequestParam String flightNo,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        FlightNumber flightNumber = FlightNumber.of(flightNo);
        return FlightLookupResponse.of(flightNumber, flightService.lookup(flightNumber, date));
    }
}
