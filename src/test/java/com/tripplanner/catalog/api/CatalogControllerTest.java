package com.tripplanner.catalog.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.catalog.api.CityResponse.AirportSummary;
import com.tripplanner.catalog.service.CatalogService;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogController.class)
class CatalogControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CatalogService catalogService;

    @Test
    @DisplayName("도시 목록은 code·nameKo·country·timezone과 공항 목록을 반환한다")
    void cities() throws Exception {
        given(catalogService.getCities()).willReturn(List.of(
                new CityResponse("OSA", "오사카", "JP", "Asia/Tokyo",
                        List.of(new AirportSummary("KIX", "간사이 국제공항")))));

        mockMvc.perform(get("/api/v1/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("OSA"))
                .andExpect(jsonPath("$[0].nameKo").value("오사카"))
                .andExpect(jsonPath("$[0].country").value("JP"))
                .andExpect(jsonPath("$[0].timezone").value("Asia/Tokyo"))
                .andExpect(jsonPath("$[0].airports[0].iata").value("KIX"))
                .andExpect(jsonPath("$[0].airports[0].nameKo").value("간사이 국제공항"));
    }

    @Test
    @DisplayName("숙소 지역 목록은 id·nameKo·좌표·설명을 반환한다")
    void areas() throws Exception {
        given(catalogService.getAreas("OSA")).willReturn(List.of(
                new AreaResponse(11L, "난바", 34.6664, 135.5011, "도톤보리·신사이바시 인접")));

        mockMvc.perform(get("/api/v1/cities/OSA/areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].nameKo").value("난바"))
                .andExpect(jsonPath("$[0].lat").value(34.6664))
                .andExpect(jsonPath("$[0].lng").value(135.5011))
                .andExpect(jsonPath("$[0].description").value("도톤보리·신사이바시 인접"));
    }

    @Test
    @DisplayName("지원하지 않는 도시 코드는 404 CITY_NOT_FOUND를 반환한다")
    void unknownCity() throws Exception {
        given(catalogService.getAreas("TYO")).willThrow(new BusinessException(ErrorCode.CITY_NOT_FOUND));

        mockMvc.perform(get("/api/v1/cities/TYO/areas"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CITY_NOT_FOUND"));
    }

    @Test
    @DisplayName("형식이 잘못된 도시 코드는 서비스 호출 없이 400 VALIDATION_FAILED를 반환한다")
    void invalidCityCode() throws Exception {
        mockMvc.perform(get("/api/v1/cities/osaka/areas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("cityCode"));

        verifyNoInteractions(catalogService);
    }
}
