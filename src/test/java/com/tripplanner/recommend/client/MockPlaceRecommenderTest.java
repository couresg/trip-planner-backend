package com.tripplanner.recommend.client;

import static com.tripplanner.place.domain.PlaceCategory.CULTURE;
import static com.tripplanner.place.domain.PlaceCategory.FOOD;
import static com.tripplanner.place.domain.PlaceCategory.NIGHT;
import static com.tripplanner.place.domain.PlaceCategory.SIGHT;
import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.recommend.client.PlaceRecommender.Candidate;
import com.tripplanner.recommend.client.PlaceRecommender.Pick;
import com.tripplanner.recommend.client.PlaceRecommender.Query;
import com.tripplanner.recommend.domain.Pace;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MockPlaceRecommenderTest {

    private final MockPlaceRecommender recommender = new MockPlaceRecommender();

    private static final List<Candidate> CANDIDATES = List.of(
            candidate(501, SIGHT, "천수각 전망대와 넓은 공원", 90, false),
            candidate(502, SIGHT, "신세카이의 상징 전망탑", 60, false),
            candidate(504, FOOD, "해산물·길거리 음식 시장", 60, false),
            candidate(506, FOOD, "골목 식당가", 45, true),
            candidate(513, NIGHT, null, 90, true),
            candidate(516, CULTURE, "오래된 절", 60, false));

    @Test
    @DisplayName("관심 카테고리를 enum 순서로 번갈아 고르고, 카테고리 안에서는 id 순이다")
    void roundRobinAcrossInterests() {
        // FOOD를 먼저 넘겨도 enum 순서(SIGHT → FOOD)로 번갈아 고른다
        List<Pick> picks = recommender.recommend(query(EnumSet.of(FOOD, SIGHT), 4));

        assertThat(picks).extracting(Pick::placeId).containsExactly(501L, 504L, 502L, 506L);
    }

    @Test
    @DisplayName("관심 카테고리 장소가 모자라면 관심 밖 장소를 id 순으로 채우고 개수를 넘지 않는다")
    void fillsWithOtherCategories() {
        List<Pick> picks = recommender.recommend(query(EnumSet.of(FOOD), 4));

        assertThat(picks).extracting(Pick::placeId).containsExactly(504L, 506L, 501L, 502L);
    }

    @Test
    @DisplayName("이유는 시드 사실(카테고리·요약·체류 시간·상시 개방)만으로 만든 한국어 문장이다")
    void reasonFromFacts() {
        List<Pick> picks = recommender.recommend(query(EnumSet.of(FOOD, NIGHT), 6));

        assertThat(picks.get(0).reason()).isEqualTo("맛집 관심사와 맞아요 · 해산물·길거리 음식 시장 · 체류 약 60분");
        assertThat(picks.get(1).reason()).isEqualTo("야경 관심사와 맞아요 · 체류 약 90분 · 상시 개방");
        assertThat(picks.get(2).reason()).isEqualTo("맛집 관심사와 맞아요 · 골목 식당가 · 체류 약 45분 · 상시 개방");
        assertThat(picks.get(3).reason())
                .isEqualTo("관심 카테고리 밖(관광)이지만 일정을 채울 후보예요 · 천수각 전망대와 넓은 공원 · 체류 약 90분");
    }

    @Test
    @DisplayName("같은 입력이면 항상 같은 결과다")
    void deterministic() {
        Query query = query(EnumSet.of(SIGHT, CULTURE), 5);

        assertThat(recommender.recommend(query)).isEqualTo(recommender.recommend(query));
    }

    private static Query query(Set<PlaceCategory> categories, int count) {
        return new Query(categories, Pace.NORMAL, count, CANDIDATES);
    }

    private static Candidate candidate(long id, PlaceCategory category, String summary, int durationMin,
            boolean alwaysOpen) {
        return new Candidate(id, "장소" + id, category, summary, durationMin, alwaysOpen);
    }
}
