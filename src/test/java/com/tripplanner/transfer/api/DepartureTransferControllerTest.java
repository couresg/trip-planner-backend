package com.tripplanner.transfer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.api.ArrivalTransferResponse.FlightCheck;
import com.tripplanner.transfer.api.DepartureTransferResponse.Departure;
import com.tripplanner.transfer.api.DepartureTransferResponse.DepartureLabel;
import com.tripplanner.transfer.api.DepartureTransferResponse.Option;
import com.tripplanner.transfer.domain.TransportMode;
import com.tripplanner.transfer.service.DepartureTransferService;
import com.tripplanner.transfer.service.TransferProperties;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.service.FlightCheckResult;
import com.tripplanner.trip.service.FlightRefreshService;
import com.tripplanner.trip.service.FlightRefreshService.FlightRefresh;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(DepartureTransferController.class)
@Import(DepartureTransferControllerTest.PropertiesConfig.class)
class DepartureTransferControllerTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";
    private static final String URL = "/api/v1/trips/" + TRIP_ID + "/transfers/departure";

    @TestConfiguration
    @EnableConfigurationProperties(TransferProperties.class)
    static class PropertiesConfig {
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    DepartureTransferService departureTransferService;

    @MockitoBean
    FlightRefreshService flightRefreshService;

    @BeforeEach
    void stubRefresh() {
        given(flightRefreshService.refresh(any(), any(), any())).willReturn(new FlightRefresh(Map.of(
                FlightDirection.OUTBOUND, FlightCheckResult.REFRESHED,
                FlightDirection.RETURN, FlightCheckResult.REFRESHED)));
    }

    @Test
    @DisplayName("응답은 공항 오프셋 시각·timezone·label·outsideWindow·leaveByAt을 담는다")
    void responseShape() throws Exception {
        given(departureTransferService.departure(TRIP_ID, TOKEN, 150, FlightCheckResult.REFRESHED)).willReturn(sampleResponse());

        mockMvc.perform(departure("?airportBufferMin=150", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightDepartureAt").value("2026-11-09T18:00:00+09:00"))
                .andExpect(jsonPath("$.airportDeadline").value("2026-11-09T15:30:00+09:00"))
                .andExpect(jsonPath("$.windowStart").value("2026-11-09T14:00:00+09:00"))
                .andExpect(jsonPath("$.revisedDepartureAt").value("2026-11-09T19:30:00+09:00"))
                .andExpect(jsonPath("$.scheduledDepartureAt").value("2026-11-09T18:00:00+09:00"))
                .andExpect(jsonPath("$.flightStatus").value("Delayed"))
                .andExpect(jsonPath("$.flightCheck.result").value("FAILED"))
                .andExpect(jsonPath("$.flightCheck.checkedAt").value("2026-11-09T10:00:00+09:00"))
                .andExpect(jsonPath("$.boardingMarginMin").value(10))
                .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.firstTrainTooLate").value(false))
                .andExpect(jsonPath("$.noTimeAfterLanding").value(false))
                .andExpect(jsonPath("$.latestLeaveAt").value("2026-11-09T14:30:00+09:00"))
                .andExpect(jsonPath("$.options[0].routeId").value(201))
                .andExpect(jsonPath("$.options[0].mode").value("RAIL"))
                .andExpect(jsonPath("$.options[0].from").value("난바역"))
                .andExpect(jsonPath("$.options[0].to").value("간사이공항역"))
                .andExpect(jsonPath("$.options[0].durationMin").value(38))
                .andExpect(jsonPath("$.options[0].walkBeforeMin").value(5))
                .andExpect(jsonPath("$.options[0].fare.amount").value(1490))
                .andExpect(jsonPath("$.options[0].is24h").value(false))
                .andExpect(jsonPath("$.options[0].departures[0].leaveAreaAt").value("2026-11-09T14:00:00+09:00"))
                .andExpect(jsonPath("$.options[0].departures[0].departAt").value("2026-11-09T14:05:00+09:00"))
                .andExpect(jsonPath("$.options[0].departures[0].arriveAirportAt").value("2026-11-09T14:43:00+09:00"))
                .andExpect(jsonPath("$.options[0].departures[0].label").value("SAFER"))
                .andExpect(jsonPath("$.options[0].departures[0].outsideWindow").value(false))
                .andExpect(jsonPath("$.options[0].departures[1].label").value("LATEST"))
                .andExpect(jsonPath("$.options[0].leaveByAt").isEmpty())
                .andExpect(jsonPath("$.options[0].dataAsOf").value("2026-11-03"))
                .andExpect(jsonPath("$.options[0].source").value("SEED"))
                // label 없음: null로 직렬화
                .andExpect(jsonPath("$.options[1].departures[0].label").isEmpty())
                .andExpect(jsonPath("$.options[1].departures[0].outsideWindow").value(true))
                // 택시
                .andExpect(jsonPath("$.options[2].mode").value("TAXI"))
                .andExpect(jsonPath("$.options[2].is24h").value(true))
                .andExpect(jsonPath("$.options[2].departures").isEmpty())
                .andExpect(jsonPath("$.options[2].leaveByAt").value("2026-11-09T14:40:00+09:00"));
    }

    @Test
    @DisplayName("airportBufferMin을 생략하면 기본 120분으로 조회한다")
    void defaultBuffer() throws Exception {
        given(departureTransferService.departure(TRIP_ID, TOKEN, 120, FlightCheckResult.REFRESHED)).willReturn(sampleResponse());

        mockMvc.perform(departure("", TOKEN)).andExpect(status().isOk());

        verify(departureTransferService).departure(TRIP_ID, TOKEN, 120, FlightCheckResult.REFRESHED);
    }

    @Test
    @DisplayName("airportBufferMin이 120/150/180이 아니면 여행을 조회하지 않고 400 VALIDATION_FAILED")
    void invalidBuffer() throws Exception {
        mockMvc.perform(departure("?airportBufferMin=90", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(departure("?airportBufferMin=abc", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("airportBufferMin"));

        verifyNoInteractions(departureTransferService, flightRefreshService);
    }

    @Test
    @DisplayName("없는 여행은 404 TRIP_NOT_FOUND, 토큰 불일치는 403 FORBIDDEN_TRIP")
    void notFoundAndForbidden() throws Exception {
        given(departureTransferService.departure("unknown", TOKEN, 120, FlightCheckResult.REFRESHED))
                .willThrow(new BusinessException(ErrorCode.TRIP_NOT_FOUND, "여행을 찾을 수 없습니다."));
        given(departureTransferService.departure(TRIP_ID, null, 120, FlightCheckResult.REFRESHED))
                .willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP, "이 여행에 접근할 권한이 없습니다."));

        mockMvc.perform(get("/api/v1/trips/unknown/transfers/departure").header("X-Owner-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TRIP"));
    }

    private static MockHttpServletRequestBuilder departure(String query, String token) {
        return get(URL + query).header("X-Owner-Token", token);
    }

    private static DepartureTransferResponse sampleResponse() {
        LocalDate asOf = LocalDate.of(2026, 11, 3);
        Option rapit = new Option(201, TransportMode.RAIL, "난카이 라피트", "난바역", "간사이공항역", 38, 5,
                Fare.of(1490, 1490, "JPY"), "난카이 난바역 3층", false,
                List.of(
                        new Departure(time("2026-11-09T14:00:00+09:00"), time("2026-11-09T14:05:00+09:00"),
                                time("2026-11-09T14:43:00+09:00"), DepartureLabel.SAFER, false),
                        new Departure(time("2026-11-09T14:30:00+09:00"), time("2026-11-09T14:35:00+09:00"),
                                time("2026-11-09T15:13:00+09:00"), DepartureLabel.LATEST, false)),
                null, asOf, "SEED", "공식 시간표 기준");
        Option express = new Option(202, TransportMode.RAIL, "난카이 공항급행", "난바역", "간사이공항역", 45, 5,
                Fare.of(970, 970, "JPY"), null, false,
                List.of(new Departure(time("2026-11-09T11:55:00+09:00"), time("2026-11-09T12:00:00+09:00"),
                        time("2026-11-09T12:45:00+09:00"), null, true)),
                null, asOf, "SEED", null);
        Option taxi = new Option(208, TransportMode.TAXI, "택시", "난바", "간사이공항", 50, 0,
                Fare.of(17000, 20000, "JPY"), null, true, List.of(), time("2026-11-09T14:40:00+09:00"), asOf, "SEED",
                null);
        return new DepartureTransferResponse(time("2026-11-09T18:00:00+09:00"), time("2026-11-09T19:30:00+09:00"),
                time("2026-11-09T15:30:00+09:00"), time("2026-11-09T14:00:00+09:00"), "Asia/Tokyo", 10, false, false,
                time("2026-11-09T14:30:00+09:00"), time("2026-11-09T18:00:00+09:00"), "Delayed",
                new FlightCheck(FlightCheckResult.FAILED, time("2026-11-09T10:00:00+09:00")),
                List.of(rapit, express, taxi));
    }

    private static OffsetDateTime time(String iso) {
        return OffsetDateTime.parse(iso);
    }
}
