package com.tripplanner.common.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.api.FlightController;
import com.tripplanner.flight.service.FlightService;
import com.tripplanner.trip.api.TripController;
import com.tripplanner.trip.service.TripService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 실제 필터 체인 + GlobalExceptionHandler로 429 응답 형식을 검증한다.
 * 컨텍스트(버킷)는 테스트끼리 공유되므로 테스트마다 다른 IP를 쓴다.
 */
@WebMvcTest(controllers = {FlightController.class, TripController.class, HealthController.class},
        properties = {"app.rate-limit.general-per-minute=3", "app.rate-limit.external-per-minute=2"})
class RateLimitMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    FlightService flightService;

    @MockitoBean
    TripService tripService;

    @Test
    @DisplayName("항공편 조회가 external 한도를 넘으면 컨트롤러를 부르지 않고 RATE_LIMITED ProblemDetail과 Retry-After를 준다")
    void externalLimited() throws Exception {
        given(flightService.lookup(any(), any())).willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND));

        mockMvc.perform(lookup("10.1.0.1")).andExpect(status().isNotFound());
        mockMvc.perform(lookup("10.1.0.1")).andExpect(status().isNotFound());
        mockMvc.perform(lookup("10.1.0.1"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://trip-planner/errors/RATE_LIMITED"))
                .andExpect(jsonPath("$.title").value("요청 한도 초과"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail").value("요청이 너무 많습니다. 30초 후에 다시 시도하세요."))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        verify(flightService, times(2)).lookup(any(), any());
    }

    @Test
    @DisplayName("여행 생성과 항공편 조회는 같은 external 한도를 쓴다 (본문 검증 실패도 한 번으로 센다)")
    void createTripSharesExternal() throws Exception {
        given(flightService.lookup(any(), any())).willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND));

        mockMvc.perform(lookup("10.1.0.2")).andExpect(status().isNotFound());
        mockMvc.perform(createTrip("10.1.0.2")).andExpect(status().isBadRequest());
        mockMvc.perform(createTrip("10.1.0.2"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("external 요청도 general을 소비하므로 그 뒤 일반 요청이 general 한도에 걸린다")
    void generalLimited() throws Exception {
        given(flightService.lookup(any(), any())).willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND));

        mockMvc.perform(lookup("10.1.0.3")).andExpect(status().isNotFound());
        mockMvc.perform(lookup("10.1.0.3")).andExpect(status().isNotFound());
        mockMvc.perform(withIp(get("/api/v1/trips"), "10.1.0.3")).andExpect(status().isOk());
        mockMvc.perform(withIp(get("/api/v1/trips"), "10.1.0.3"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "20"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("HEAD 항공편 조회도 external 한도에 걸린다")
    void headLimited() throws Exception {
        given(flightService.lookup(any(), any())).willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND));

        mockMvc.perform(withIp(head("/api/v1/flights/lookup").param("flightNo", "KE723").param("date", "2026-11-05"),
                "10.1.0.5")).andExpect(status().isNotFound());
        mockMvc.perform(withIp(head("/api/v1/flights/lookup").param("flightNo", "KE723").param("date", "2026-11-05"),
                "10.1.0.5")).andExpect(status().isNotFound());
        mockMvc.perform(withIp(head("/api/v1/flights/lookup").param("flightNo", "KE723").param("date", "2026-11-05"),
                        "10.1.0.5"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"));
        mockMvc.perform(lookup("10.1.0.5")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("헬스체크와 CORS preflight(OPTIONS)는 한도와 무관하다")
    void healthAndOptionsSkipped() throws Exception {
        given(flightService.lookup(any(), any())).willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(withIp(get("/api/v1/health"), "10.1.0.4")).andExpect(status().isOk());
            mockMvc.perform(withIp(options("/api/v1/flights/lookup"), "10.1.0.4")).andExpect(status().isOk());
        }
        mockMvc.perform(lookup("10.1.0.4")).andExpect(status().isNotFound());
    }

    private static MockHttpServletRequestBuilder lookup(String ip) {
        return withIp(get("/api/v1/flights/lookup").param("flightNo", "KE723").param("date", "2026-11-05"), ip);
    }

    private static MockHttpServletRequestBuilder createTrip(String ip) {
        return withIp(post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content("{}"), ip);
    }

    private static MockHttpServletRequestBuilder withIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }
}
