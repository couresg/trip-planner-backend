package com.tripplanner.flight.client;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightPoint;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AeroDataBox {@code /flights/number} 응답(항공편 객체 배열)을 FlightLeg 목록으로 변환한다. Mock·실제 클라이언트가 함께 쓴다.
 * 선택 필드(revisedTime, terminal, status, airport.timeZone)가 없어도 처리하고,
 * 공항 IATA나 예정 시각이 없는 구간은 계산에 쓸 수 없으므로 건너뛴다.
 */
@Component
public class AeroDataBoxParser {

    private static final Logger log = LoggerFactory.getLogger(AeroDataBoxParser.class);
    private static final String UNKNOWN_STATUS = "Unknown";

    private final ObjectMapper objectMapper;

    public AeroDataBoxParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @throws FlightProviderException JSON이 아니거나 최상위가 배열이 아닐 때 */
    public List<FlightLeg> parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JacksonException e) {
            throw new FlightProviderException("AeroDataBox 응답 JSON 해석 실패", e);
        }
        if (!root.isArray()) {
            throw new FlightProviderException("AeroDataBox 응답이 배열이 아님", null);
        }
        List<FlightLeg> legs = new ArrayList<>();
        for (JsonNode flight : root) {
            FlightPoint departure = point(flight.path("departure"));
            FlightPoint arrival = point(flight.path("arrival"));
            if (departure == null || arrival == null) {
                log.debug("필수 필드가 없는 구간을 건너뜀: number={}", text(flight.path("number")));
                continue;
            }
            String status = text(flight.path("status"));
            legs.add(new FlightLeg(departure, arrival, status == null ? UNKNOWN_STATUS : status));
        }
        return legs;
    }

    private static FlightPoint point(JsonNode node) {
        String iata = text(node.path("airport").path("iata"));
        OffsetDateTime scheduled = time(node.path("scheduledTime"));
        if (iata == null || scheduled == null) {
            return null;
        }
        OffsetDateTime revised = time(node.path("revisedTime"));
        ZoneId zone = zone(text(node.path("airport").path("timeZone")), scheduled.getOffset());
        if (zone instanceof ZoneOffset && parseTime(text(node.path("scheduledTime").path("local"))) == null) {
            log.warn("공항 timezone과 현지 시각이 없어 UTC로 표시함: iata={}", iata);
        }
        return new FlightPoint(iata, zone, scheduled.toInstant(), revised == null ? null : revised.toInstant(),
                text(node.path("terminal")));
    }

    /** {"utc": "2026-11-05 03:00Z", "local": "2026-11-05 12:00+09:00"} 중 utc를 우선 쓴다. */
    private static OffsetDateTime time(JsonNode node) {
        OffsetDateTime utc = parseTime(text(node.path("utc")));
        OffsetDateTime local = parseTime(text(node.path("local")));
        if (utc != null && local != null) {
            // 공항 timezone이 없을 때 현지 오프셋을 쓸 수 있도록 local의 오프셋으로 맞춘다.
            return utc.withOffsetSameInstant(local.getOffset());
        }
        return utc != null ? utc : local;
    }

    private static OffsetDateTime parseTime(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.replace(' ', 'T'));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static ZoneId zone(String timeZone, ZoneOffset fallback) {
        if (timeZone != null) {
            try {
                return ZoneId.of(timeZone);
            } catch (DateTimeException e) {
                log.debug("알 수 없는 공항 timezone: {}", timeZone);
            }
        }
        return fallback;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
