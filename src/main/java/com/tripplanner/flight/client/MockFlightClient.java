package com.tripplanner.flight.client;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.domain.FlightPoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * external.mock=true(기본)일 때 쓰는 공급자. classpath:fixtures/aerodatabox/{편명}.json (AeroDataBox 응답 형식)을 읽고,
 * 가장 이른 구간의 출발 현지 날짜와 요청 날짜의 차이만큼 모든 시각을 옮겨서 어떤 날짜로 조회해도 같은 시각표를 돌려준다.
 * fixture가 없는 편명은 빈 목록(찾지 못함)이다.
 */
@Component
@ConditionalOnProperty(name = "external.mock", havingValue = "true", matchIfMissing = true)
public class MockFlightClient implements FlightClient {

    static final String FIXTURE_DIR = "fixtures/aerodatabox/";

    private final AeroDataBoxParser parser;

    public MockFlightClient(AeroDataBoxParser parser) {
        this.parser = parser;
    }

    @Override
    public List<FlightLeg> findLegs(FlightNumber flightNumber, LocalDate departureLocalDate) {
        // FlightNumber는 영문 대문자·숫자만 허용하므로 경로 조작이 불가능하다.
        ClassPathResource fixture = new ClassPathResource(FIXTURE_DIR + flightNumber.value() + ".json");
        if (!fixture.exists()) {
            return List.of();
        }
        List<FlightLeg> legs = parser.parse(read(fixture));
        LocalDate fixtureDate = legs.stream()
                .map(leg -> localDate(leg.departure()))
                .min(Comparator.naturalOrder())
                .orElse(departureLocalDate);
        long days = ChronoUnit.DAYS.between(fixtureDate, departureLocalDate);
        return legs.stream()
                .map(leg -> new FlightLeg(shift(leg.departure(), days), shift(leg.arrival(), days), leg.status()))
                .toList();
    }

    private static String read(ClassPathResource fixture) {
        try {
            return fixture.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LocalDate localDate(FlightPoint point) {
        return point.scheduled().atZone(point.timezone()).toLocalDate();
    }

    private static FlightPoint shift(FlightPoint point, long days) {
        return new FlightPoint(point.iata(), point.timezone(), shift(point.scheduled(), point.timezone(), days),
                point.revised() == null ? null : shift(point.revised(), point.timezone(), days), point.terminal());
    }

    /** 현지 날짜 기준으로 옮긴다 (DST가 있는 공항에서도 현지 시각이 유지되도록). */
    private static Instant shift(Instant instant, ZoneId zone, long days) {
        return instant.atZone(zone).plusDays(days).toInstant();
    }
}
