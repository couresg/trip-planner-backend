package com.tripplanner.trip.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.trip.domain.FlightSource;
import com.tripplanner.trip.service.TripService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(TripController.class)
class TripControllerTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";
    private static final String BODY = """
            {"outboundFlightNo":"KE723","returnFlightNo":"KE724","cityCode":"OSA","areaId":11,
             "startDate":"2026-11-05","endDate":"2026-11-08","manualOverride":null}
            """;

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    TripService tripService;

    // ===== POST /trips =====

    @Test
    @DisplayName("여행을 생성하면 201과 Location 헤더, tripId·ownerToken을 반환한다")
    void create() throws Exception {
        given(tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", 11L, LocalDate.of(2026, 11, 5),
                LocalDate.of(2026, 11, 8), null))).willReturn(new CreateTripResponse(TRIP_ID, TOKEN));

        mockMvc.perform(postTrip(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/trips/" + TRIP_ID))
                .andExpect(jsonPath("$.tripId").value(TRIP_ID))
                .andExpect(jsonPath("$.ownerToken").value(TOKEN));
    }

    @Test
    @DisplayName("수동 입력은 방향별로 받으며 오프셋 포함 시각으로 해석된다")
    void createManualOutbound() throws Exception {
        given(tripService.create(any())).willReturn(new CreateTripResponse(TRIP_ID, TOKEN));

        mockMvc.perform(postTrip("""
                        {"outboundFlightNo":"7C1302","returnFlightNo":"KE724","cityCode":"OSA","areaId":11,
                         "startDate":"2026-11-05","endDate":"2026-11-08",
                         "manualOverride":{"outboundArrivalAt":"2026-11-06T00:20:00+09:00","outboundArrivalAirport":"KIX"}}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tripId").value(TRIP_ID));

        ArgumentCaptor<CreateTripRequest> captor = ArgumentCaptor.forClass(CreateTripRequest.class);
        verify(tripService).create(captor.capture());
        ManualOverride manual = captor.getValue().manualOverride();
        assertThat(manual.outboundArrivalAt().toInstant()).isEqualTo(Instant.parse("2026-11-05T15:20:00Z"));
        assertThat(manual.outboundArrivalAirport()).isEqualTo("KIX");
        assertThat(manual.hasOutbound()).isTrue();
        assertThat(manual.hasReturn()).isFalse();
    }

    @Test
    @DisplayName("수동 입력 쌍이 반만 채워지면 400 VALIDATION_FAILED")
    void createHalfManualPair() throws Exception {
        mockMvc.perform(postTrip("""
                        {"outboundFlightNo":"KE723","returnFlightNo":"KE724","cityCode":"OSA","areaId":11,
                         "startDate":"2026-11-05","endDate":"2026-11-08",
                         "manualOverride":{"returnDepartureAt":"2026-11-08T15:00:00+09:00"}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("manualOverride.returnPairComplete"));

        verifyNoInteractions(tripService);
    }

    @Test
    @DisplayName("수동 입력 공항 코드가 영문 3자리가 아니면 400 VALIDATION_FAILED")
    void createInvalidManualAirport() throws Exception {
        mockMvc.perform(postTrip("""
                        {"outboundFlightNo":"KE723","returnFlightNo":"KE724","cityCode":"OSA","areaId":11,
                         "startDate":"2026-11-05","endDate":"2026-11-08",
                         "manualOverride":{"outboundArrivalAt":"2026-11-05T13:45:00+09:00","outboundArrivalAirport":"KI1"}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("manualOverride.outboundArrivalAirport"));
        mockMvc.perform(postTrip("""
                        {"outboundFlightNo":"KE723","returnFlightNo":"KE724","cityCode":"OSA","areaId":11,
                         "startDate":"2026-11-05","endDate":"2026-11-08",
                         "manualOverride":{"returnDepartureAt":"2026-11-08T15:00:00+09:00","returnDepartureAirport":"KIXX"}}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("manualOverride.returnDepartureAirport"));

        verifyNoInteractions(tripService);
    }

    @Test
    @DisplayName("필수 값 누락·도시 코드 형식 오류·날짜 형식 오류는 400 VALIDATION_FAILED")
    void createInvalidBody() throws Exception {
        mockMvc.perform(postTrip("""
                        {"outboundFlightNo":"KE723","returnFlightNo":"KE724","cityCode":"osa",
                         "startDate":"2026-11-05","endDate":"2026-11-08"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(2));
        mockMvc.perform(postTrip(BODY.replace("2026-11-08", "2026-11-31")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(postTrip("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(tripService);
    }

    @Test
    @DisplayName("서비스 오류는 각 오류 코드와 상태로 변환된다")
    void createServiceErrors() throws Exception {
        List<BusinessException> errors = List.of(
                new BusinessException(ErrorCode.INVALID_FLIGHT_NO),
                new BusinessException(ErrorCode.INVALID_DATE_RANGE),
                new BusinessException(ErrorCode.CITY_AIRPORT_MISMATCH,
                        "선택한 여행 도시(오사카)와 도착 공항(NRT)이 다릅니다."),
                new BusinessException(ErrorCode.CITY_NOT_FOUND),
                new BusinessException(ErrorCode.VALIDATION_FAILED, "선택한 도시(오사카)의 숙소 지역이 아닙니다."),
                new BusinessException(ErrorCode.FLIGHT_NOT_FOUND),
                new FlightProviderException("AeroDataBox HTTP 503", null));
        for (BusinessException error : errors) {
            willThrow(error).given(tripService).create(any());

            mockMvc.perform(postTrip(BODY))
                    .andExpect(status().is(error.errorCode().status().value()))
                    .andExpect(jsonPath("$.code").value(error.errorCode().name()))
                    .andExpect(jsonPath("$.detail").value(error.getMessage()));
        }
    }

    // ===== GET /trips =====

    @Test
    @DisplayName("목록은 X-Owner-Token 헤더 값을 그대로 서비스에 넘기고 배열을 반환한다")
    void list() throws Exception {
        given(tripService.list("a,b")).willReturn(List.of(new TripSummaryResponse(TRIP_ID,
                new TripSummaryResponse.CityRef("OSA", "오사카"), new TripSummaryResponse.AreaRef(11L, "난바"),
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8), "KE723", "KE724")));

        mockMvc.perform(get("/api/v1/trips").header(TripController.OWNER_TOKEN_HEADER, "a,b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tripId").value(TRIP_ID))
                .andExpect(jsonPath("$[0].city.code").value("OSA"))
                .andExpect(jsonPath("$[0].city.nameKo").value("오사카"))
                .andExpect(jsonPath("$[0].area.id").value(11))
                .andExpect(jsonPath("$[0].area.nameKo").value("난바"))
                .andExpect(jsonPath("$[0].startDate").value("2026-11-05"))
                .andExpect(jsonPath("$[0].endDate").value("2026-11-08"))
                .andExpect(jsonPath("$[0].outboundFlightNo").value("KE723"))
                .andExpect(jsonPath("$[0].returnFlightNo").value("KE724"));
    }

    @Test
    @DisplayName("헤더가 없으면 200 빈 배열")
    void listWithoutHeader() throws Exception {
        given(tripService.list(null)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/trips"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("토큰이 50개를 넘으면 400 VALIDATION_FAILED")
    void listTooManyTokens() throws Exception {
        given(tripService.list("many")).willThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));

        mockMvc.perform(get("/api/v1/trips").header(TripController.OWNER_TOKEN_HEADER, "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ===== GET /trips/{tripId} =====

    @Test
    @DisplayName("상세는 명세 형식(return 키, source, status 포함)으로 현지 오프셋 시각을 반환한다")
    void detail() throws Exception {
        given(tripService.get(TRIP_ID, TOKEN)).willReturn(new TripDetailResponse(TRIP_ID,
                new TripDetailResponse.CityInfo("OSA", "오사카", "Asia/Tokyo"),
                new TripDetailResponse.AreaInfo(11L, "난바", 34.6664, 135.5011),
                LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 8),
                new TripDetailResponse.Outbound("7C1302", "KIX", OffsetDateTime.parse("2026-11-06T00:20:00+09:00"),
                        FlightSource.AERODATABOX, "Expected"),
                new TripDetailResponse.Return("KE724", "KIX", OffsetDateTime.parse("2026-11-08T15:00:00+09:00"),
                        FlightSource.MANUAL, "Manual")));

        mockMvc.perform(get("/api/v1/trips/" + TRIP_ID).header(TripController.OWNER_TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripId").value(TRIP_ID))
                .andExpect(jsonPath("$.city.code").value("OSA"))
                .andExpect(jsonPath("$.city.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.area.id").value(11))
                .andExpect(jsonPath("$.area.lat").value(34.6664))
                .andExpect(jsonPath("$.area.lng").value(135.5011))
                .andExpect(jsonPath("$.startDate").value("2026-11-05"))
                .andExpect(jsonPath("$.endDate").value("2026-11-08"))
                .andExpect(jsonPath("$.outbound.flightNo").value("7C1302"))
                .andExpect(jsonPath("$.outbound.arrivalAirport").value("KIX"))
                .andExpect(jsonPath("$.outbound.arrivalAt").value("2026-11-06T00:20:00+09:00"))
                .andExpect(jsonPath("$.outbound.source").value("AERODATABOX"))
                .andExpect(jsonPath("$.outbound.status").value("Expected"))
                .andExpect(jsonPath("$.return.flightNo").value("KE724"))
                .andExpect(jsonPath("$.return.departureAirport").value("KIX"))
                .andExpect(jsonPath("$.return.departureAt").value("2026-11-08T15:00:00+09:00"))
                .andExpect(jsonPath("$.return.source").value("MANUAL"))
                .andExpect(jsonPath("$.return.status").value("Manual"))
                .andExpect(jsonPath("$.returnFlight").doesNotExist());
    }

    @Test
    @DisplayName("상세: 없는 여행은 404 TRIP_NOT_FOUND, 토큰 불일치는 403 FORBIDDEN_TRIP")
    void detailErrors() throws Exception {
        given(tripService.get("not-a-uuid", TOKEN)).willThrow(new BusinessException(ErrorCode.TRIP_NOT_FOUND));
        given(tripService.get(TRIP_ID, null)).willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP));

        mockMvc.perform(get("/api/v1/trips/not-a-uuid").header(TripController.OWNER_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/trips/" + TRIP_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TRIP"));
    }

    // ===== DELETE /trips/{tripId} =====

    @Test
    @DisplayName("삭제는 204를 반환한다")
    void deleteTrip() throws Exception {
        mockMvc.perform(delete("/api/v1/trips/" + TRIP_ID).header(TripController.OWNER_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNoContent());

        verify(tripService).delete(TRIP_ID, TOKEN);
    }

    @Test
    @DisplayName("삭제: 토큰 불일치는 403, 없는 여행은 404")
    void deleteErrors() throws Exception {
        willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP)).given(tripService).delete(TRIP_ID, "wrong");
        willThrow(new BusinessException(ErrorCode.TRIP_NOT_FOUND)).given(tripService).delete("x", TOKEN);

        mockMvc.perform(delete("/api/v1/trips/" + TRIP_ID).header(TripController.OWNER_TOKEN_HEADER, "wrong"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TRIP"));
        mockMvc.perform(delete("/api/v1/trips/x").header(TripController.OWNER_TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder postTrip(String body) {
        return post("/api/v1/trips").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
