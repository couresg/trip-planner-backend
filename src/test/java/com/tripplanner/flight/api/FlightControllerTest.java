package com.tripplanner.flight.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.client.AeroDataBoxParser;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.flight.client.MockFlightClient;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.service.FlightService;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(FlightController.class)
class FlightControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    /** 서비스 응답은 fixture로 만든다 (Mock 공급자와 같은 데이터). */
    private static final MockFlightClient FIXTURES =
            new MockFlightClient(new AeroDataBoxParser(JsonMapper.builder().build()));

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FlightService flightService;

    @Test
    @DisplayName("소문자·공백 편명은 정규화되어 조회되고, 시각은 공항 현지 오프셋으로 반환된다")
    void lookupNormalized() throws Exception {
        givenFixture("KE723");

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "ke 723").param("date", "2026-11-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNo").value("KE723"))
                .andExpect(jsonPath("$.legs.length()").value(1))
                .andExpect(jsonPath("$.legs[0].departure.iata").value("ICN"))
                .andExpect(jsonPath("$.legs[0].departure.scheduled").value("2026-11-05T12:00:00+09:00"))
                .andExpect(jsonPath("$.legs[0].departure.revised").isEmpty())
                .andExpect(jsonPath("$.legs[0].departure.terminal").value("2"))
                .andExpect(jsonPath("$.legs[0].arrival.iata").value("KIX"))
                .andExpect(jsonPath("$.legs[0].arrival.scheduled").value("2026-11-05T13:45:00+09:00"))
                .andExpect(jsonPath("$.legs[0].arrival.revised").value("2026-11-05T13:52:00+09:00"))
                .andExpect(jsonPath("$.legs[0].arrival.terminal").value("1"))
                .andExpect(jsonPath("$.legs[0].status").value("Expected"));
    }

    @Test
    @DisplayName("22:30 출발 편은 다음 날 00:20(+09:00) 도착으로 반환된다")
    void midnightCrossing() throws Exception {
        givenFixture("7C1302");

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "7C1302").param("date", "2026-11-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs[0].departure.scheduled").value("2026-11-05T22:30:00+09:00"))
                .andExpect(jsonPath("$.legs[0].arrival.scheduled").value("2026-11-06T00:20:00+09:00"));
    }

    @Test
    @DisplayName("지연 편은 출발·도착 revised를 함께 반환한다")
    void delayed() throws Exception {
        givenFixture("OZ112");

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "OZ112").param("date", "2026-11-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs[0].status").value("Delayed"))
                .andExpect(jsonPath("$.legs[0].departure.revised").value("2026-11-05T10:30:00+09:00"))
                .andExpect(jsonPath("$.legs[0].arrival.scheduled").value("2026-11-05T10:50:00+09:00"))
                .andExpect(jsonPath("$.legs[0].arrival.revised").value("2026-11-05T12:20:00+09:00"));
    }

    @Test
    @DisplayName("결항 편도 200으로 반환하고 status로 결항을 알린다")
    void canceled() throws Exception {
        givenFixture("LJ231");

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "LJ231").param("date", "2026-11-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs[0].status").value("Canceled"))
                .andExpect(jsonPath("$.legs[0].arrival.terminal").isEmpty());
    }

    @Test
    @DisplayName("다구간 편명은 모든 구간을 반환한다")
    void multiLeg() throws Exception {
        FlightNumber tw = FlightNumber.of("TW9901");
        given(flightService.lookup(tw, DATE)).willReturn(FIXTURES.findLegs(tw, DATE).reversed());

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "TW9901").param("date", "2026-11-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs.length()").value(2))
                .andExpect(jsonPath("$.legs[0].departure.iata").value("ICN"))
                .andExpect(jsonPath("$.legs[0].arrival.iata").value("FUK"))
                .andExpect(jsonPath("$.legs[1].departure.iata").value("FUK"))
                .andExpect(jsonPath("$.legs[1].arrival.iata").value("KIX"));
    }

    @Test
    @DisplayName("찾지 못한 편명은 404 FLIGHT_NOT_FOUND를 반환한다")
    void notFound() throws Exception {
        given(flightService.lookup(FlightNumber.of("ZZ9999"), DATE))
                .willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "ZZ9999").param("date", "2026-11-05"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
    }

    @Test
    @DisplayName("공급자 장애는 503 FLIGHT_PROVIDER_UNAVAILABLE을 반환하고 내부 원인은 노출하지 않는다")
    void providerUnavailable() throws Exception {
        given(flightService.lookup(FlightNumber.of("KE723"), DATE))
                .willThrow(new FlightProviderException("AeroDataBox HTTP 429", null));

        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "KE723").param("date", "2026-11-05"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FLIGHT_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE.title()));
    }

    @Test
    @DisplayName("형식이 틀린 편명은 400 INVALID_FLIGHT_NO를 반환하고 서비스를 호출하지 않는다")
    void invalidFlightNo() throws Exception {
        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "KE72345").param("date", "2026-11-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FLIGHT_NO"));
        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "").param("date", "2026-11-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FLIGHT_NO"));

        verifyNoInteractions(flightService);
    }

    @Test
    @DisplayName("날짜가 없거나 형식이 틀리면 400 VALIDATION_FAILED를 반환한다")
    void invalidDate() throws Exception {
        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "KE723"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        mockMvc.perform(get("/api/v1/flights/lookup").param("flightNo", "KE723").param("date", "2026-13-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        mockMvc.perform(get("/api/v1/flights/lookup").param("date", "2026-11-05"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("flightNo"));

        verifyNoInteractions(flightService);
    }

    private void givenFixture(String flightNo) {
        FlightNumber number = FlightNumber.of(flightNo);
        given(flightService.lookup(number, DATE)).willReturn(FIXTURES.findLegs(number, DATE));
    }
}
