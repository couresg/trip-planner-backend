package com.tripplanner.recommend.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.place.api.PlaceResponse;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.recommend.domain.Pace;
import com.tripplanner.recommend.service.PlaceRecommendationService;
import com.tripplanner.trip.api.TripController;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(PlaceRecommendationController.class)
class PlaceRecommendationControllerTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String PATH = "/api/v1/trips/" + TRIP_ID + "/recommendations/places";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PlaceRecommendationService recommendationService;

    @Test
    @DisplayName("콤마 구분 카테고리(중복 포함)를 집합으로 넘기고 순위·이유·장소를 응답한다")
    void recommend() throws Exception {
        given(recommendationService.recommend(TRIP_ID, "tok", EnumSet.of(PlaceCategory.SIGHT, PlaceCategory.FOOD),
                Pace.NORMAL)).willReturn(new PlaceRecommendationResponse(Pace.NORMAL, 3, 4, 19, List.of(
                        new PlaceRecommendationResponse.Recommendation(1, "관광 관심사와 맞아요",
                                new PlaceResponse(501L, "오사카성", PlaceCategory.SIGHT, 34.6873, 135.5262, List.of(),
                                        false, 90, "천수각 전망대와 넓은 공원")))));

        mockMvc.perform(request("FOOD,SIGHT,FOOD", "NORMAL").header(TripController.OWNER_TOKEN_HEADER, "tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pace").value("NORMAL"))
                .andExpect(jsonPath("$.placesPerDay").value(3))
                .andExpect(jsonPath("$.dayCount").value(4))
                .andExpect(jsonPath("$.candidateCount").value(19))
                .andExpect(jsonPath("$.recommendations[0].rank").value(1))
                .andExpect(jsonPath("$.recommendations[0].reason").value("관광 관심사와 맞아요"))
                .andExpect(jsonPath("$.recommendations[0].place.id").value(501))
                .andExpect(jsonPath("$.recommendations[0].place.nameKo").value("오사카성"))
                .andExpect(jsonPath("$.recommendations[0].place.durationMin").value(90));
    }

    @Test
    @DisplayName("카테고리는 파라미터를 반복해서 넘길 수도 있다")
    void repeatedCategories() throws Exception {
        given(recommendationService.recommend(TRIP_ID, null, EnumSet.of(PlaceCategory.NIGHT, PlaceCategory.CULTURE),
                Pace.PACKED)).willReturn(new PlaceRecommendationResponse(Pace.PACKED, 5, 1, 0, List.of()));

        mockMvc.perform(get(PATH).param("categories", "NIGHT").param("categories", "CULTURE").param("pace", "PACKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placesPerDay").value(5));
    }

    @ParameterizedTest(name = "categories={0}, pace={1}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL, NORMAL",
            "'', NORMAL",
            "',', NORMAL",
            "UNKNOWN, NORMAL",
            "sight, NORMAL",
            "SIGHT, NULL",
            "SIGHT, ''",
            "SIGHT, FAST",
            "SIGHT, normal"})
    @DisplayName("카테고리·밀도가 없거나 비었거나 정의되지 않은 값이면 여행 조회 전에 VALIDATION_FAILED(400)")
    void validation(String categories, String pace) throws Exception {
        MockHttpServletRequestBuilder request = get(PATH);
        if (categories != null) {
            request.param("categories", categories);
        }
        if (pace != null) {
            request.param("pace", pace);
        }

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(recommendationService);
    }

    @Test
    @DisplayName("추천기 장애는 RECOMMENDATION_UNAVAILABLE(503) ProblemDetail이다")
    void providerUnavailable() throws Exception {
        given(recommendationService.recommend(any(), any(), any(), any())).willThrow(new BusinessException(
                ErrorCode.RECOMMENDATION_UNAVAILABLE, "지금은 장소를 추천할 수 없습니다. 잠시 후 다시 시도하세요."));

        mockMvc.perform(request("SIGHT", "NORMAL"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RECOMMENDATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value("지금은 장소를 추천할 수 없습니다. 잠시 후 다시 시도하세요."));
    }

    private static MockHttpServletRequestBuilder request(String categories, String pace) {
        return get(PATH).param("categories", categories).param("pace", pace);
    }
}
