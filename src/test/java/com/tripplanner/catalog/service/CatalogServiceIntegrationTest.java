package com.tripplanner.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripplanner.catalog.api.AreaResponse;
import com.tripplanner.catalog.api.CityResponse;
import com.tripplanner.catalog.api.CityResponse.AirportSummary;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.place.api.PlaceResponse;
import com.tripplanner.place.api.PlaceResponse.OpeningHour;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.service.PlaceService;
import com.tripplanner.support.MySqlContainerConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 전체 컨텍스트(ddl-auto=validate 포함) + Testcontainers MySQL로 카탈로그·장소 서비스를 검증한다. */
@Tag("integration")
@SpringBootTest
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class CatalogServiceIntegrationTest {

    @Autowired
    CatalogService catalogService;

    @Autowired
    PlaceService placeService;

    @Test
    @DisplayName("도시 목록에 오사카와 간사이 국제공항이 포함된다")
    void cities() {
        List<CityResponse> cities = catalogService.getCities();

        assertThat(cities).containsExactly(new CityResponse("OSA", "오사카", "JP", "Asia/Tokyo",
                List.of(new AirportSummary("KIX", "간사이 국제공항"))));
    }

    @Test
    @DisplayName("오사카 숙소 지역은 정렬 순서대로 좌표와 함께 반환된다")
    void areas() {
        List<AreaResponse> areas = catalogService.getAreas("OSA");

        assertThat(areas).extracting(AreaResponse::nameKo).containsExactly("난바", "우메다", "신오사카", "텐노지");
        assertThat(areas.getFirst()).isEqualTo(
                new AreaResponse(11L, "난바", 34.6664, 135.5011, "도톤보리·신사이바시 인접, 난카이선으로 공항 직결"));
    }

    @Test
    @DisplayName("시드되지 않은 도시 코드는 CITY_NOT_FOUND 예외를 던진다")
    void unknownCity() {
        assertThatThrownBy(() -> catalogService.getAreas("TYO"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CITY_NOT_FOUND);
        assertThatThrownBy(() -> placeService.getPlaces("TYO", null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("장소 영업시간 JSON은 요일별 목록으로 변환되고, 휴관일은 목록에서 빠진다")
    void placeOpeningHours() {
        PlaceResponse museum = placeService.getPlaces("OSA", PlaceCategory.CULTURE, "시립미술관").getFirst();

        assertThat(museum.alwaysOpen()).isFalse();
        assertThat(museum.openingHours()).hasSize(6)
                .extracting(OpeningHour::dayOfWeek).doesNotContain("MON");
        assertThat(museum.openingHours().getFirst()).isEqualTo(new OpeningHour("TUE", "09:30", "17:00"));
    }

    @Test
    @DisplayName("상시 개방 장소는 빈 영업시간과 alwaysOpen=true로 반환된다")
    void alwaysOpenPlace() {
        PlaceResponse dotonbori = placeService.getPlaces("OSA", null, "도톤보리").getFirst();

        assertThat(dotonbori.alwaysOpen()).isTrue();
        assertThat(dotonbori.openingHours()).isEmpty();
    }

    @Test
    @DisplayName("검색어의 %·_ 는 와일드카드가 아닌 문자로 취급되고, 앞뒤 공백은 무시된다")
    void queryEscaping() {
        assertThat(placeService.getPlaces("OSA", null, "%")).isEmpty();
        assertThat(placeService.getPlaces("OSA", null, "_")).isEmpty();
        assertThat(placeService.getPlaces("OSA", null, "  오사카성  "))
                .extracting(PlaceResponse::nameKo).containsExactly("오사카성");
        assertThat(placeService.getPlaces("OSA", null, "   ")).hasSizeGreaterThanOrEqualTo(15);
    }
}
