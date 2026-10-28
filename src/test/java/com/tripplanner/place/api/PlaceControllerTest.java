package com.tripplanner.place.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.place.api.PlaceResponse.OpeningHour;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.service.PlaceService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PlaceController.class)
class PlaceControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    PlaceService placeService;

    @Test
    @DisplayName("카테고리와 검색어로 장소를 조회하면 영업시간을 포함한 목록을 반환한다")
    void places() throws Exception {
        given(placeService.getPlaces("OSA", PlaceCategory.SIGHT, "오사카")).willReturn(List.of(
                new PlaceResponse(501L, "오사카성", PlaceCategory.SIGHT, 34.6873, 135.5262,
                        List.of(new OpeningHour("MON", "09:00", "17:00")), false, 90, "천수각 전망대와 넓은 공원")));

        mockMvc.perform(get("/api/v1/cities/OSA/places").param("category", "SIGHT").param("q", "오사카"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(501))
                .andExpect(jsonPath("$[0].nameKo").value("오사카성"))
                .andExpect(jsonPath("$[0].category").value("SIGHT"))
                .andExpect(jsonPath("$[0].lat").value(34.6873))
                .andExpect(jsonPath("$[0].lng").value(135.5262))
                .andExpect(jsonPath("$[0].openingHours[0].dayOfWeek").value("MON"))
                .andExpect(jsonPath("$[0].openingHours[0].open").value("09:00"))
                .andExpect(jsonPath("$[0].openingHours[0].close").value("17:00"))
                .andExpect(jsonPath("$[0].alwaysOpen").value(false))
                .andExpect(jsonPath("$[0].durationMin").value(90))
                .andExpect(jsonPath("$[0].summary").value("천수각 전망대와 넓은 공원"));
    }

    @Test
    @DisplayName("카테고리와 검색어 없이 조회하면 null 조건으로 서비스를 호출한다")
    void placesWithoutFilters() throws Exception {
        given(placeService.getPlaces("OSA", null, null)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/cities/OSA/places"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        verify(placeService).getPlaces("OSA", null, null);
    }

    @Test
    @DisplayName("정의되지 않은 카테고리는 400 VALIDATION_FAILED를 반환한다")
    void unknownCategory() throws Exception {
        mockMvc.perform(get("/api/v1/cities/OSA/places").param("category", "BEACH"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("category"));

        verifyNoInteractions(placeService);
    }

    @Test
    @DisplayName("50자를 넘는 검색어는 400 VALIDATION_FAILED를 반환한다")
    void tooLongQuery() throws Exception {
        mockMvc.perform(get("/api/v1/cities/OSA/places").param("q", "가".repeat(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("q"));

        verifyNoInteractions(placeService);
    }

    @Test
    @DisplayName("지원하지 않는 도시의 장소를 조회하면 404 CITY_NOT_FOUND를 반환한다")
    void unknownCity() throws Exception {
        given(placeService.getPlaces("TYO", null, null)).willThrow(new BusinessException(ErrorCode.CITY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/cities/TYO/places"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CITY_NOT_FOUND"));
    }
}
