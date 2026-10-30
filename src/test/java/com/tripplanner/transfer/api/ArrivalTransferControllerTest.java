package com.tripplanner.transfer.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Departure;
import com.tripplanner.transfer.api.ArrivalTransferResponse.DepartureStatus;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import com.tripplanner.transfer.api.ArrivalTransferResponse.Option;
import com.tripplanner.transfer.domain.TransportMode;
import com.tripplanner.transfer.service.ArrivalTransferService;
import com.tripplanner.transfer.service.TransferProperties;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

@WebMvcTest(ArrivalTransferController.class)
@Import(ArrivalTransferControllerTest.PropertiesConfig.class)
class ArrivalTransferControllerTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";
    private static final String URL = "/api/v1/trips/" + TRIP_ID + "/transfers/arrival";

    @TestConfiguration
    @EnableConfigurationProperties(TransferProperties.class)
    static class PropertiesConfig {
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ArrivalTransferService arrivalTransferService;

    @Test
    @DisplayName("응답은 공항 오프셋 시각(초 포함)·timezone·is24h를 담고 요금은 고정/범위/없음 형태로 직렬화된다")
    void responseShape() throws Exception {
        given(arrivalTransferService.arrival(TRIP_ID, TOKEN, 60)).willReturn(sampleResponse());

        mockMvc.perform(arrival("?immigrationMin=60", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseTime").value("2026-11-05T13:52:00+09:00"))
                .andExpect(jsonPath("$.windowStart").value("2026-11-05T13:52:00+09:00"))
                .andExpect(jsonPath("$.windowEnd").value("2026-11-05T15:52:00+09:00"))
                .andExpect(jsonPath("$.readyAt").value("2026-11-05T14:52:00+09:00"))
                .andExpect(jsonPath("$.timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$.noTransitWarning").value(false))
                // 고정 요금 철도
                .andExpect(jsonPath("$.options[0].routeId").value(101))
                .andExpect(jsonPath("$.options[0].mode").value("RAIL"))
                .andExpect(jsonPath("$.options[0].name").value("난카이 라피트"))
                .andExpect(jsonPath("$.options[0].from").value("간사이공항역"))
                .andExpect(jsonPath("$.options[0].to").value("난바역"))
                .andExpect(jsonPath("$.options[0].durationMin").value(38))
                .andExpect(jsonPath("$.options[0].walkAfterMin").value(5))
                .andExpect(jsonPath("$.options[0].fare.amount").value(1490))
                .andExpect(jsonPath("$.options[0].fare.currency").value("JPY"))
                .andExpect(jsonPath("$.options[0].fare.display").value("¥1,490"))
                .andExpect(jsonPath("$.options[0].fare.min").doesNotExist())
                .andExpect(jsonPath("$.options[0].fare.max").doesNotExist())
                .andExpect(jsonPath("$.options[0].boardingTip").value("2층 연결통로 → 난카이선 개찰구"))
                .andExpect(jsonPath("$.options[0].is24h").value(false))
                .andExpect(jsonPath("$.options[0]['24h']").doesNotExist())
                .andExpect(jsonPath("$.options[0].departures[0].departAt").value("2026-11-05T14:35:00+09:00"))
                .andExpect(jsonPath("$.options[0].departures[0].arriveAreaAt").value("2026-11-05T15:18:00+09:00"))
                .andExpect(jsonPath("$.options[0].departures[0].status").value("HARD_TO_CATCH"))
                .andExpect(jsonPath("$.options[0].departures[0].recommended").value(false))
                .andExpect(jsonPath("$.options[0].departures[1].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.options[0].departures[1].recommended").value(true))
                .andExpect(jsonPath("$.options[0].nextFirstDeparture").isEmpty())
                .andExpect(jsonPath("$.options[0].dataAsOf").value("2026-10-01"))
                .andExpect(jsonPath("$.options[0].source").value("SEED"))
                .andExpect(jsonPath("$.options[0].note").value("추정"))
                // 운행 종료 + 첫차, 요금 없음
                .andExpect(jsonPath("$.options[1].fare").isEmpty())
                .andExpect(jsonPath("$.options[1].nextFirstDeparture").value("2026-11-06T05:45:00+09:00"))
                .andExpect(jsonPath("$.options[1].note").isEmpty())
                // 택시: 범위 요금, 24시간
                .andExpect(jsonPath("$.options[2].mode").value("TAXI"))
                .andExpect(jsonPath("$.options[2].is24h").value(true))
                .andExpect(jsonPath("$.options[2].departures").isEmpty())
                .andExpect(jsonPath("$.options[2].fare.min").value(17000))
                .andExpect(jsonPath("$.options[2].fare.max").value(20000))
                .andExpect(jsonPath("$.options[2].fare.display").value("¥17,000~20,000"))
                .andExpect(jsonPath("$.options[2].fare.amount").doesNotExist());
    }

    @Test
    @DisplayName("immigrationMin을 생략하면 기본 60분으로 조회한다")
    void defaultImmigration() throws Exception {
        given(arrivalTransferService.arrival(TRIP_ID, TOKEN, 60)).willReturn(sampleResponse());

        mockMvc.perform(arrival("", TOKEN)).andExpect(status().isOk());

        verify(arrivalTransferService).arrival(TRIP_ID, TOKEN, 60);
    }

    @Test
    @DisplayName("immigrationMin이 30/60/90이 아니면 여행을 조회하지 않고 400 VALIDATION_FAILED")
    void invalidImmigration() throws Exception {
        mockMvc.perform(arrival("?immigrationMin=45", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(arrival("?immigrationMin=abc", TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("immigrationMin"));

        verifyNoInteractions(arrivalTransferService);
    }

    @Test
    @DisplayName("없는 여행은 404 TRIP_NOT_FOUND, 토큰 불일치는 403 FORBIDDEN_TRIP")
    void notFoundAndForbidden() throws Exception {
        given(arrivalTransferService.arrival("unknown", TOKEN, 60))
                .willThrow(new BusinessException(ErrorCode.TRIP_NOT_FOUND, "여행을 찾을 수 없습니다."));
        given(arrivalTransferService.arrival(TRIP_ID, null, 60))
                .willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP, "이 여행에 접근할 권한이 없습니다."));

        mockMvc.perform(get("/api/v1/trips/unknown/transfers/arrival").header("X-Owner-Token", TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        mockMvc.perform(get(URL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TRIP"));
    }

    private static MockHttpServletRequestBuilder arrival(String query, String token) {
        return get(URL + query).header("X-Owner-Token", token);
    }

    private static ArrivalTransferResponse sampleResponse() {
        LocalDate asOf = LocalDate.of(2026, 10, 1);
        Option rapit = new Option(101, TransportMode.RAIL, "난카이 라피트", "간사이공항역", "난바역", 38, 5,
                Fare.of(1490, 1490, "JPY"), "2층 연결통로 → 난카이선 개찰구", false,
                List.of(
                        new Departure(time("2026-11-05T14:35:00+09:00"), time("2026-11-05T15:18:00+09:00"),
                                DepartureStatus.HARD_TO_CATCH, false),
                        new Departure(time("2026-11-05T15:05:00+09:00"), time("2026-11-05T15:48:00+09:00"),
                                DepartureStatus.AVAILABLE, true)),
                null, asOf, "SEED", "추정");
        Option ended = new Option(102, TransportMode.RAIL, "난카이 공항급행", "간사이공항역", "난바역", 45, 5,
                Fare.of(null, null, "JPY"), null, false, List.of(), time("2026-11-06T05:45:00+09:00"), asOf, "SEED",
                null);
        Option taxi = new Option(108, TransportMode.TAXI, "택시", "택시 승강장", "난바", 50, 0,
                Fare.of(17000, 20000, "JPY"), null, true, List.of(), null, asOf, "SEED", null);
        return new ArrivalTransferResponse(time("2026-11-05T13:52:00+09:00"), time("2026-11-05T13:52:00+09:00"),
                time("2026-11-05T15:52:00+09:00"), time("2026-11-05T14:52:00+09:00"), "Asia/Tokyo", false,
                List.of(rapit, ended, taxi));
    }

    private static OffsetDateTime time(String iso) {
        return OffsetDateTime.parse(iso);
    }
}
