package com.tripplanner.recommend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.tripplanner.catalog.domain.City;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.itinerary.repository.ItineraryItemRepository;
import com.tripplanner.place.api.PlaceResponse;
import com.tripplanner.place.domain.Place;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.repository.PlaceRepository;
import com.tripplanner.place.service.PlaceService;
import com.tripplanner.recommend.api.PlaceRecommendationResponse;
import com.tripplanner.recommend.api.PlaceRecommendationResponse.Recommendation;
import com.tripplanner.recommend.client.PlaceRecommender;
import com.tripplanner.recommend.client.PlaceRecommender.Pick;
import com.tripplanner.recommend.client.PlaceRecommender.Query;
import com.tripplanner.recommend.domain.Pace;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.service.TripService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** 가짜 추천기로 서비스 계층의 결과 검증(후보 밖·중복 ID 제거, 개수, 이유 정리), 실패 변환, 캐시를 확인한다. */
class PlaceRecommendationServiceTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "token";

    private final TripService tripService = mock(TripService.class);
    private final PlaceRepository placeRepository = mock(PlaceRepository.class);
    private final ItineraryItemRepository itemRepository = mock(ItineraryItemRepository.class);
    private final PlaceService placeService = mock(PlaceService.class);
    private final FakeRecommender recommender = new FakeRecommender();
    private final Trip trip = mock(Trip.class);
    private PlaceRecommendationService service;

    @BeforeEach
    void setUp() {
        City city = mock(City.class);
        given(city.getId()).willReturn(1L);
        given(trip.getCity()).willReturn(city);
        // 2일 여행
        given(trip.getStartDate()).willReturn(LocalDate.of(2030, 3, 15));
        given(trip.getEndDate()).willReturn(LocalDate.of(2030, 3, 16));
        given(tripService.findOwned(TRIP_ID, TOKEN)).willReturn(trip);
        given(itemRepository.findPlaceIdsByTrip(trip)).willReturn(List.of());
        List<Place> places = List.of(place(501, PlaceCategory.SIGHT), place(502, PlaceCategory.SIGHT),
                place(504, PlaceCategory.FOOD), place(506, PlaceCategory.FOOD), place(513, PlaceCategory.NIGHT));
        given(placeRepository.search(1L, null, null)).willReturn(places);
        given(placeService.toResponse(any())).willAnswer(inv -> {
            Place p = inv.getArgument(0);
            return new PlaceResponse(p.getId(), p.getNameKo(), p.getCategory(), 0, 0, List.of(), false,
                    p.getDurationMin(), p.getSummary());
        });
        service = new PlaceRecommendationService(tripService, placeRepository, itemRepository, placeService,
                recommender, mock(PlatformTransactionManager.class), RecommendProperties.defaults());
    }

    @Test
    @DisplayName("추천기가 후보에 없는 ID·null·중복 ID를 돌려주면 버리고 순위를 1부터 다시 매긴다")
    void discardsUnknownAndDuplicateIds() {
        recommender.answer = q -> Arrays.asList(new Pick(999L, "지어낸 장소"), new Pick(504L, "시장"), null,
                new Pick(null, "ID 없음"), new Pick(504L, "중복"), new Pick(1501L, "다른 도시"), new Pick(501L, "성"));

        PlaceRecommendationResponse response = recommend(Set.of(PlaceCategory.FOOD), Pace.RELAXED);

        assertThat(response.recommendations()).extracting(Recommendation::rank).containsExactly(1, 2);
        assertThat(response.recommendations()).extracting(r -> r.place().id()).containsExactly(504L, 501L);
        assertThat(response.recommendations()).extracting(Recommendation::reason).containsExactly("시장", "성");
    }

    @Test
    @DisplayName("개수는 일수 × 하루 장소 수이고 추천기가 더 돌려줘도 그만큼만 남긴다")
    void capsAtDaysTimesPace() {
        recommender.answer = q -> q.candidates().stream().map(c -> new Pick(c.id(), "이유")).toList();

        PlaceRecommendationResponse response = recommend(Set.of(PlaceCategory.SIGHT), Pace.RELAXED);

        assertThat(recommender.queries).singleElement().extracting(Query::count).isEqualTo(4);
        assertThat(response.recommendations()).hasSize(4);
        assertThat(response.dayCount()).isEqualTo(2);
        assertThat(response.placesPerDay()).isEqualTo(2);
        assertThat(response.candidateCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("개수가 후보 수보다 많으면 후보 수로 자르고, 일정에 있는 장소는 후보에서 뺀다")
    void excludesScheduledAndCapsAtCandidates() {
        given(itemRepository.findPlaceIdsByTrip(trip)).willReturn(List.of(501L, 513L));
        recommender.answer = q -> q.candidates().stream().map(c -> new Pick(c.id(), "이유")).toList();

        PlaceRecommendationResponse response = recommend(Set.of(PlaceCategory.SIGHT), Pace.PACKED);

        Query query = recommender.queries.getFirst();
        assertThat(query.count()).isEqualTo(3);
        assertThat(query.candidates()).extracting(PlaceRecommender.Candidate::id).containsExactly(502L, 504L, 506L);
        assertThat(response.candidateCount()).isEqualTo(3);
        assertThat(response.recommendations()).extracting(r -> r.place().id()).containsExactly(502L, 504L, 506L);
    }

    @Test
    @DisplayName("후보가 없으면 추천기를 부르지 않고 빈 목록을 준다")
    void noCandidates() {
        given(itemRepository.findPlaceIdsByTrip(trip)).willReturn(List.of(501L, 502L, 504L, 506L, 513L));

        PlaceRecommendationResponse response = recommend(Set.of(PlaceCategory.SIGHT), Pace.NORMAL);

        assertThat(response.recommendations()).isEmpty();
        assertThat(response.candidateCount()).isZero();
        assertThat(recommender.queries).isEmpty();
    }

    @Test
    @DisplayName("이유는 앞뒤 공백을 지우고 200자로 자르며, 비어 있으면 시드 사실로 만든 기본 문장으로 바꾼다")
    void normalizesReason() {
        recommender.answer = q -> List.of(new Pick(501L, "  " + "가".repeat(250) + " "), new Pick(502L, "   "),
                new Pick(504L, null));

        List<String> reasons = recommend(Set.of(PlaceCategory.SIGHT), Pace.RELAXED).recommendations().stream()
                .map(Recommendation::reason).toList();

        assertThat(reasons.get(0)).isEqualTo("가".repeat(PlaceRecommendationService.REASON_MAX_LENGTH));
        assertThat(reasons.get(1)).isEqualTo("관광 · 요약502 · 체류 약 60분");
        assertThat(reasons.get(2)).isEqualTo("맛집 · 요약504 · 체류 약 60분");
    }

    @Test
    @DisplayName("이유의 제어 문자·줄바꿈·탭은 공백 하나로 바뀌고 연속 공백은 하나로 줄어든다")
    void stripsControlCharsAndCollapsesWhitespace() {
        Place place = place(501, PlaceCategory.SIGHT);

        assertThat(PlaceRecommendationService.reason(" 첫 줄\n\n둘째\t줄\u0000끝\u001b[31m   빨강 ", place))
                .isEqualTo("첫 줄 둘째 줄 끝 [31m 빨강");
        assertThat(PlaceRecommendationService.reason("\n\t\u0007", place)).isEqualTo("관광 · 요약501 · 체류 약 60분");
    }

    @Test
    @DisplayName("이유는 코드 포인트 기준 200자로 자르므로 이모지(서로게이트 쌍)가 깨지지 않는다")
    void cutsByCodePoints() {
        Place place = place(501, PlaceCategory.SIGHT);
        String emoji = "😀"; // 😀, UTF-16 두 칸

        String mixed = PlaceRecommendationService.reason("가".repeat(199) + emoji + emoji, place);
        assertThat(mixed).isEqualTo("가".repeat(199) + emoji);

        String emojis = PlaceRecommendationService.reason(emoji.repeat(250), place);
        assertThat(emojis.codePointCount(0, emojis.length())).isEqualTo(200);
        assertThat(emojis).isEqualTo(emoji.repeat(200));
        assertThat(Character.isHighSurrogate(emojis.charAt(emojis.length() - 1))).isFalse();

        // 정확히 200자는 그대로
        assertThat(PlaceRecommendationService.reason(emoji.repeat(200), place)).isEqualTo(emoji.repeat(200));
    }

    @Test
    @DisplayName("추천기 예외는 RECOMMENDATION_UNAVAILABLE로 바꾸고 캐시하지 않아 다음 요청에서 다시 부른다")
    void providerFailure() {
        recommender.answer = q -> {
            throw new IllegalStateException("timeout secret-detail");
        };

        assertThatThrownBy(() -> recommend(Set.of(PlaceCategory.SIGHT), Pace.NORMAL))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.RECOMMENDATION_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("secret-detail");
                });

        recommender.answer = q -> List.of(new Pick(501L, "성"));
        assertThat(recommend(Set.of(PlaceCategory.SIGHT), Pace.NORMAL).recommendations()).hasSize(1);
        assertThat(recommender.queries).hasSize(2);
    }

    @Test
    @DisplayName("같은 입력은 캐시에서 주고, 밀도·카테고리·후보가 바뀌면 다시 부른다")
    void cachesSameInput() {
        recommender.answer = q -> List.of(new Pick(501L, "성"));

        recommend(Set.of(PlaceCategory.SIGHT, PlaceCategory.FOOD), Pace.NORMAL);
        recommend(Set.of(PlaceCategory.FOOD, PlaceCategory.SIGHT), Pace.NORMAL);
        assertThat(recommender.queries).hasSize(1);

        recommend(Set.of(PlaceCategory.SIGHT, PlaceCategory.FOOD), Pace.RELAXED);
        recommend(Set.of(PlaceCategory.SIGHT), Pace.NORMAL);
        assertThat(recommender.queries).hasSize(3);

        given(itemRepository.findPlaceIdsByTrip(trip)).willReturn(List.of(513L));
        recommend(Set.of(PlaceCategory.SIGHT), Pace.NORMAL);
        assertThat(recommender.queries).hasSize(4);
    }

    @Test
    @DisplayName("추천기는 관심 카테고리·밀도와 후보의 시드 사실을 받는다")
    void passesFactsToRecommender() {
        recommender.answer = q -> List.of();

        recommend(Set.of(PlaceCategory.NIGHT), Pace.NORMAL);

        Query query = recommender.queries.getFirst();
        assertThat(query.categories()).containsExactly(PlaceCategory.NIGHT);
        assertThat(query.pace()).isEqualTo(Pace.NORMAL);
        assertThat(query.candidates().getFirst())
                .isEqualTo(new PlaceRecommender.Candidate(501L, "장소501", PlaceCategory.SIGHT, "요약501", 60, false));
    }

    private PlaceRecommendationResponse recommend(Set<PlaceCategory> categories, Pace pace) {
        return service.recommend(TRIP_ID, TOKEN, categories, pace);
    }

    private static Place place(long id, PlaceCategory category) {
        Place place = mock(Place.class);
        given(place.getId()).willReturn(id);
        given(place.getNameKo()).willReturn("장소" + id);
        given(place.getCategory()).willReturn(category);
        given(place.getSummary()).willReturn("요약" + id);
        given(place.getDurationMin()).willReturn(60);
        return place;
    }

    private static final class FakeRecommender implements PlaceRecommender {

        Function<Query, List<Pick>> answer = q -> List.of();
        final List<Query> queries = new ArrayList<>();

        @Override
        public List<Pick> recommend(Query query) {
            queries.add(query);
            return answer.apply(query);
        }
    }
}
