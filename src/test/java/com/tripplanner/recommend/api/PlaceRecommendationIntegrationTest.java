package com.tripplanner.recommend.api;

import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tripplanner.support.MySqlContainerConfig;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.api.ManualOverride;
import com.tripplanner.trip.api.TripController;
import com.tripplanner.trip.service.TripService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * test 프로필(Mock 추천기) + Testcontainers MySQL 시드로 장소 추천 API를 HTTP부터 DB까지 검증한다.
 * 오사카 2030-03-15 ~ 03-18(4일), 장소 501~519 (SIGHT 501~503, FOOD 504~506, NIGHT 513~515).
 */
@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig.class)
@ActiveProfiles("test")
class PlaceRecommendationIntegrationTest {

    private static final String MISSING_TRIP = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TripService tripService;

    @Test
    @DisplayName("관심 카테고리를 번갈아 고르고 일수 × 밀도만큼 시드 사실과 함께 추천한다")
    void recommendsFromSeed() throws Exception {
        CreateTripResponse trip = createTrip();

        recommend(trip, "SIGHT,FOOD", "RELAXED", trip.ownerToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pace").value("RELAXED"))
                .andExpect(jsonPath("$.placesPerDay").value(2))
                .andExpect(jsonPath("$.dayCount").value(4))
                .andExpect(jsonPath("$.candidateCount").value(19))
                .andExpect(jsonPath("$.recommendations", hasSize(8)))
                .andExpect(jsonPath("$.recommendations[*].rank").value(contains(1, 2, 3, 4, 5, 6, 7, 8)))
                // SIGHT·FOOD 번갈아 6곳 → 관심 밖 id 순(507, 508)
                .andExpect(jsonPath("$.recommendations[*].place.id")
                        .value(contains(501, 504, 502, 505, 503, 506, 507, 508)))
                .andExpect(jsonPath("$.recommendations[0].reason")
                        .value("관광 관심사와 맞아요 · 천수각 전망대와 넓은 공원 · 체류 약 90분"))
                .andExpect(jsonPath("$.recommendations[0].place.nameKo").value("오사카성"))
                .andExpect(jsonPath("$.recommendations[0].place.lat").value(34.6873))
                .andExpect(jsonPath("$.recommendations[0].place.openingHours[0].open").value("09:00"))
                .andExpect(jsonPath("$.recommendations[6].reason").value(startsWith(
                        "관심 카테고리 밖(쇼핑)")));
    }

    @Test
    @DisplayName("일정에 넣은 장소(어느 날짜든)는 후보와 추천에서 빠지고, 결과는 후보 수를 넘지 않는다")
    void excludesScheduledPlaces() throws Exception {
        CreateTripResponse trip = createTrip();
        addItem(trip, 501, "2030-03-16");
        addItem(trip, 513, "2030-03-17");

        recommend(trip, "SIGHT,NIGHT", "PACKED", trip.ownerToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidateCount").value(17))
                .andExpect(jsonPath("$.recommendations", hasSize(17)))
                .andExpect(jsonPath("$.recommendations[*].place.id", not(hasItem(501))))
                .andExpect(jsonPath("$.recommendations[*].place.id", not(hasItem(513))))
                .andExpect(jsonPath("$.recommendations[0].place.id").value(502))
                .andExpect(jsonPath("$.recommendations[1].place.id").value(514));
    }

    @Test
    @DisplayName("다른 도시의 장소는 후보가 아니다")
    void onlyTripCity() throws Exception {
        CreateTripResponse trip = createTrip();

        recommend(trip, "CULTURE", "PACKED", trip.ownerToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendations[*].place.id",
                        everyItem(both(greaterThanOrEqualTo(501))
                                .and(lessThanOrEqualTo(519)))));
    }

    @Test
    @DisplayName("없는·형식 오류 여행은 404, 토큰 누락·불일치는 403")
    void ownership() throws Exception {
        CreateTripResponse trip = createTrip();

        recommend(MISSING_TRIP, "SIGHT", "NORMAL", trip.ownerToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        recommend("not-a-uuid", "SIGHT", "NORMAL", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        recommend(trip.tripId().toString(), "SIGHT", "NORMAL", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TRIP"));
        recommend(trip.tripId().toString(), "SIGHT", "NORMAL", "00000000-0000-0000-0000-000000000000")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_TRIP"));
    }

    @Test
    @DisplayName("형식 검증(400)이 여행 조회(404)보다 먼저다")
    void validationBeforeLookup() throws Exception {
        recommend(MISSING_TRIP, "UNKNOWN", "NORMAL", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        recommend(MISSING_TRIP, "", "NORMAL", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(get(path(MISSING_TRIP)).param("categories", "SIGHT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private CreateTripResponse createTrip() {
        return tripService.create(new CreateTripRequest("KE723", "KE724", "OSA", 11L, LocalDate.of(2030, 3, 15),
                LocalDate.of(2030, 3, 18), new ManualOverride(OffsetDateTime.parse("2030-03-15T14:00:00+09:00"),
                        "KIX", OffsetDateTime.parse("2030-03-18T18:00:00+09:00"), "KIX")));
    }

    private void addItem(CreateTripResponse trip, long placeId, String date) throws Exception {
        mockMvc.perform(post("/api/v1/trips/" + trip.tripId() + "/itinerary/items")
                        .header(TripController.OWNER_TOKEN_HEADER, trip.ownerToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeId\":" + placeId + ",\"date\":\"" + date + "\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions recommend(CreateTripResponse trip, String categories, String pace, String token)
            throws Exception {
        return recommend(trip.tripId().toString(), categories, pace, token);
    }

    private ResultActions recommend(String tripId, String categories, String pace, String token) throws Exception {
        var request = get(path(tripId)).param("categories", categories).param("pace", pace);
        return mockMvc.perform(token == null ? request : request.header(TripController.OWNER_TOKEN_HEADER, token));
    }

    private static String path(String tripId) {
        return "/api/v1/trips/" + tripId + "/recommendations/places";
    }
}
