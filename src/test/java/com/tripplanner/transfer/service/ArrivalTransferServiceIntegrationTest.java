package com.tripplanner.transfer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.transfer.api.ArrivalTransferResponse;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Departure;
import com.tripplanner.transfer.api.ArrivalTransferResponse.DepartureStatus;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Option;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.service.TripService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** test 프로필(external.mock=true) + Testcontainers MySQL 시드(V100)로 노선·시각표 매핑과 도착일 교통편을 검증한다. */
@Tag("integration")
@SpringBootTest
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class ArrivalTransferServiceIntegrationTest {

    /** 목요일. KE723 fixture는 현지 13:52(revised) KIX 도착 */
    private static final LocalDate START = LocalDate.of(2030, 3, 14);

    @Autowired
    TripService tripService;

    @Autowired
    ArrivalTransferService arrivalTransferService;

    @Test
    @DisplayName("KE723로 난바 여행을 만들면 시드 노선 101(라피트)·102(공항급행)·108(택시)이 도착 시각 기준으로 나온다")
    void arrivalFromSeed() {
        CreateTripResponse trip = tripService.create(
                new CreateTripRequest("KE723", "KE724", "OSA", 11L, START, START.plusDays(3), null));

        ArrivalTransferResponse response = arrivalTransferService.arrival(trip.tripId(), trip.ownerToken(), 60);

        assertThat(response.baseTime()).isEqualTo(OffsetDateTime.parse("2030-03-14T13:52:00+09:00"));
        assertThat(response.readyAt()).isEqualTo(OffsetDateTime.parse("2030-03-14T14:52:00+09:00"));
        assertThat(response.timezone()).isEqualTo("Asia/Tokyo");
        assertThat(response.noTransitWarning()).isFalse();
        assertThat(response.options()).extracting(Option::routeId).containsExactly(101L, 102L, 108L);

        Option rapit = response.options().getFirst();
        assertThat(rapit.departures()).extracting(Departure::departAt, Departure::status, Departure::recommended)
                .containsExactly(
                        tuple(OffsetDateTime.parse("2030-03-14T14:05:00+09:00"), DepartureStatus.HARD_TO_CATCH, false),
                        tuple(OffsetDateTime.parse("2030-03-14T14:35:00+09:00"), DepartureStatus.HARD_TO_CATCH, false),
                        tuple(OffsetDateTime.parse("2030-03-14T15:05:00+09:00"), DepartureStatus.AVAILABLE, true),
                        tuple(OffsetDateTime.parse("2030-03-14T15:35:00+09:00"), DepartureStatus.AVAILABLE, false));
        assertThat(rapit.fare().display()).isEqualTo("¥1,490");
        assertThat(rapit.dataAsOf()).isEqualTo(LocalDate.of(2026, 10, 1));

        Option taxi = response.options().getLast();
        assertThat(taxi.is24h()).isTrue();
        assertThat(taxi.fare().display()).isEqualTo("¥17,000~20,000");
    }
}
