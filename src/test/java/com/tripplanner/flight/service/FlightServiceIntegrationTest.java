package com.tripplanner.flight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.flight.domain.FlightCacheEntry;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.repository.FlightCacheRepository;
import com.tripplanner.support.MySqlContainerConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** test 프로필(external.mock=true) + Testcontainers MySQL로 flight_cache 매핑과 조회·저장 흐름을 검증한다. */
@Tag("integration")
@SpringBootTest
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class FlightServiceIntegrationTest {

    @Autowired
    FlightService flightService;

    @Autowired
    FlightCacheRepository cacheRepository;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("조회 결과는 정규화된 구간 JSON으로 flight_cache에 저장되고 다시 읽어도 같은 값이다")
    void lookupStoresNormalizedJson() {
        LocalDate date = LocalDate.of(2030, 1, 15);

        List<FlightLeg> legs = flightService.lookup(FlightNumber.of("7c 1302"), date);

        assertThat(legs.getFirst().arrival().scheduledLocal())
                .isEqualTo(OffsetDateTime.parse("2030-01-16T00:20:00+09:00"));
        FlightCacheEntry row = cacheRepository.findByFlightNoAndFlightDate("7C1302", date).orElseThrow();
        assertThat(objectMapper.readValue(row.getResponseJson(), FlightLeg[].class)).containsExactlyElementsOf(legs);
        assertThat(row.getResponseJson()).contains("\"2030-01-15T13:30:00Z\"", "\"Asia/Seoul\"");
        assertThat(Duration.between(row.getFetchedAt(), row.getExpiresAt())).isEqualTo(FlightService.LONG_TTL);
        assertThat(flightService.lookup(FlightNumber.of("7C1302"), date)).isEqualTo(legs);
    }

    @Test
    @DisplayName("찾지 못한 편명은 FLIGHT_NOT_FOUND이고 flight_cache에 저장하지 않는다")
    void notFoundIsNotStored() {
        LocalDate date = LocalDate.of(2030, 1, 16);

        assertThatThrownBy(() -> flightService.lookup(FlightNumber.of("ZZ9999"), date))
                .isInstanceOf(BusinessException.class);
        assertThat(cacheRepository.findByFlightNoAndFlightDate("ZZ9999", date)).isEmpty();
    }

    @Test
    @DisplayName("flight_cache 행은 (편명, 날짜)로 조회되고 Instant가 마이크로초까지 보존된다")
    void repositoryRoundTrip() {
        LocalDate date = LocalDate.of(2030, 1, 17);
        Instant fetchedAt = Instant.parse("2030-01-10T01:02:03.123456Z");
        cacheRepository.save(new FlightCacheEntry("KE724", date, "[]", fetchedAt, fetchedAt.plus(Duration.ofHours(6))));

        FlightCacheEntry found = cacheRepository.findByFlightNoAndFlightDate("KE724", date).orElseThrow();

        assertThat(found.getFetchedAt()).isEqualTo(fetchedAt);
        assertThat(found.getExpiresAt()).isEqualTo(fetchedAt.plus(Duration.ofHours(6)));
        assertThat(found.getResponseJson()).isEqualTo("[]");
        assertThat(cacheRepository.findByFlightNoAndFlightDate("KE724", date.plusDays(1))).isEmpty();
    }
}
