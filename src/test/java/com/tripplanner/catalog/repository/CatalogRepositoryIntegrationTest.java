package com.tripplanner.catalog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.domain.Area;
import com.tripplanner.catalog.domain.City;
import com.tripplanner.place.domain.Place;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.repository.PlaceRepository;
import com.tripplanner.support.MySqlContainerConfig;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Flyway V1 스키마 + V100 오사카 시드가 적용된 MySQL에서 리포지토리 쿼리를 검증한다. */
@Tag("integration")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class CatalogRepositoryIntegrationTest {

    @Autowired
    CityRepository cityRepository;

    @Autowired
    AirportRepository airportRepository;

    @Autowired
    AreaRepository areaRepository;

    @Autowired
    PlaceRepository placeRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("활성 도시로 오사카(OSA, Asia/Tokyo)가 시드되어 있다")
    void activeCities() {
        List<City> cities = cityRepository.findAllByActiveTrueOrderByIdAsc();

        assertThat(cities).extracting(City::getCode).containsExactly("OSA");
        assertThat(cities.getFirst().getTimezone()).isEqualTo("Asia/Tokyo");
        assertThat(cityRepository.findByCodeAndActiveTrue("TYO")).isEmpty();
    }

    @Test
    @DisplayName("오사카 공항으로 KIX가 조회된다")
    void airportsOfCity() {
        City osaka = cityRepository.findByCodeAndActiveTrue("OSA").orElseThrow();

        List<Airport> airports = airportRepository.findAllByCityIdInOrderByIataAsc(List.of(osaka.getId()));

        assertThat(airports).extracting(Airport::getIata).containsExactly("KIX");
        assertThat(airports.getFirst().getTimezone()).isEqualTo("Asia/Tokyo");
    }

    @Test
    @DisplayName("오사카 숙소 지역은 난바·우메다·신오사카·텐노지 순으로 조회된다")
    void areasSorted() {
        City osaka = cityRepository.findByCodeAndActiveTrue("OSA").orElseThrow();

        List<Area> areas = areaRepository.findAllByCityIdOrderBySortOrderAscIdAsc(osaka.getId());

        assertThat(areas).extracting(Area::getNameKo).containsExactly("난바", "우메다", "신오사카", "텐노지");
    }

    @Test
    @DisplayName("장소는 15개 이상이고 모든 카테고리에 하나 이상 있다")
    void placesCoverAllCategories() {
        City osaka = cityRepository.findByCodeAndActiveTrue("OSA").orElseThrow();

        List<Place> all = placeRepository.search(osaka.getId(), null, null);

        assertThat(all).hasSizeGreaterThanOrEqualTo(15);
        assertThat(all).extracting(Place::getCategory).contains(PlaceCategory.values());
    }

    @Test
    @DisplayName("카테고리 조건을 주면 해당 카테고리 장소만 조회된다")
    void searchByCategory() {
        City osaka = cityRepository.findByCodeAndActiveTrue("OSA").orElseThrow();

        List<Place> nights = placeRepository.search(osaka.getId(), PlaceCategory.NIGHT, null);

        assertThat(nights).isNotEmpty().allMatch(p -> p.getCategory() == PlaceCategory.NIGHT);
        assertThat(nights).extracting(Place::getNameKo).contains("도톤보리");
    }

    @Test
    @DisplayName("이름 패턴과 카테고리를 함께 주면 두 조건을 모두 만족하는 장소만 조회된다")
    void searchByNameAndCategory() {
        City osaka = cityRepository.findByCodeAndActiveTrue("OSA").orElseThrow();

        assertThat(placeRepository.search(osaka.getId(), null, "%오사카%"))
                .extracting(Place::getNameKo)
                .contains("오사카성", "오사카 시립미술관", "오사카 역사박물관");
        assertThat(placeRepository.search(osaka.getId(), PlaceCategory.SIGHT, "%오사카%"))
                .extracting(Place::getNameKo)
                .containsExactly("오사카성");
    }

    @Test
    @DisplayName("상시 개방 장소는 영업시간 JSON이 빈 배열이다")
    void alwaysOpenPlaceHasEmptyHours() {
        City osaka = cityRepository.findByCodeAndActiveTrue("OSA").orElseThrow();

        Place dotonbori = placeRepository.search(osaka.getId(), null, "도톤보리").getFirst();

        assertThat(dotonbori.isAlwaysOpen()).isTrue();
        assertThat(dotonbori.getOpeningHoursJson()).isEqualTo("[]");
    }

    @Test
    @DisplayName("모든 숙소 지역에 공항→지역, 지역→공항 노선이 각각 있고 출처·기준일이 채워져 있다")
    void transportRoutesBothDirections() {
        List<String> areaDirections = jdbcTemplate.queryForList("""
                select concat(a.name_ko, ':', r.direction)
                from transport_route r join area a on a.id = r.area_id
                group by a.name_ko, r.direction
                """, String.class);
        Integer missingSource = jdbcTemplate.queryForObject(
                "select count(*) from transport_route where source_url is null or source_url = '' or data_as_of is null",
                Integer.class);

        assertThat(areaDirections).containsExactlyInAnyOrder(
                "난바:AIRPORT_TO_AREA", "난바:AREA_TO_AIRPORT",
                "우메다:AIRPORT_TO_AREA", "우메다:AREA_TO_AIRPORT",
                "신오사카:AIRPORT_TO_AREA", "신오사카:AREA_TO_AIRPORT",
                "텐노지:AIRPORT_TO_AREA", "텐노지:AREA_TO_AIRPORT");
        assertThat(missingSource).isZero();
    }

    @Test
    @DisplayName("TIMETABLE 노선은 요일 유형별 시각표가 있고, HEADWAY 노선은 첫차·막차·간격이 있다")
    void schedulesMatchScheduleType() {
        Integer timetableWithoutRows = jdbcTemplate.queryForObject("""
                select count(*) from transport_route r
                where r.schedule_type = 'TIMETABLE'
                  and (select count(distinct t.day_type) from transport_timetable t where t.route_id = r.id) <> 3
                """, Integer.class);
        Integer headwayWithoutFields = jdbcTemplate.queryForObject("""
                select count(*) from transport_route
                where schedule_type = 'HEADWAY'
                  and (first_departure_min is null or last_departure_min is null or headway_min is null)
                """, Integer.class);
        Integer taxisNot24h = jdbcTemplate.queryForObject(
                "select count(*) from transport_route where mode = 'TAXI' and (is_24h = false or schedule_type <> 'NONE')",
                Integer.class);

        assertThat(timetableWithoutRows).isZero();
        assertThat(headwayWithoutFields).isZero();
        assertThat(taxisNot24h).isZero();
    }

    @Test
    @DisplayName("라피트(공항→난바) 평일 시각표에 14:35, 15:05 출발편이 있다")
    void rapitTimetable() {
        List<Integer> departures = jdbcTemplate.queryForList(
                "select departure_min from transport_timetable where route_id = ? and day_type = ? and departure_min between ? and ? order by departure_min",
                Integer.class, 101, "WEEKDAY", 14 * 60 + 30, 15 * 60 + 30);

        assertThat(departures).containsExactly(14 * 60 + 35, 15 * 60 + 5);
    }
}
