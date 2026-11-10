package com.tripplanner.recommend.client;

import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.recommend.domain.Pace;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 장소 추천기. app.recommend.provider 값에 따라 구현이 등록된다 (현재 mock만).
 * 구현은 후보 중에서 골라 순위대로 돌려주기만 하고, 후보 밖·중복 ID 제거와 개수 제한은 PlaceRecommendationService가 한다.
 */
public interface PlaceRecommender {

    /**
     * @return 순위 순서의 추천. count개를 넘거나 후보 밖 ID가 섞여도 서비스가 걸러낸다
     * @throws RuntimeException 추천기 장애 (서비스가 RECOMMENDATION_UNAVAILABLE로 바꾼다)
     */
    List<Pick> recommend(Query query);

    /** count는 1 이상이고 candidates 수를 넘지 않는다. candidates는 id 순이다. */
    record Query(Set<PlaceCategory> categories, Pace pace, int count, List<Candidate> candidates) {
    }

    /** 추천기에 넘기는 후보의 사실 정보 (DB 시드 값). */
    record Candidate(long id, String nameKo, PlaceCategory category, String summary, int durationMin,
            boolean alwaysOpen) {

        /** 시드 사실만으로 만든 설명. 예: "천수각 전망대와 넓은 공원 · 체류 약 90분" */
        public String facts() {
            List<String> parts = new ArrayList<>();
            if (summary != null && !summary.isBlank()) {
                parts.add(summary.strip());
            }
            parts.add("체류 약 " + durationMin + "분");
            if (alwaysOpen) {
                parts.add("상시 개방");
            }
            return String.join(" · ", parts);
        }
    }

    /** placeId는 후보 ID 중 하나여야 한다. reason은 한국어 한두 문장. */
    record Pick(Long placeId, String reason) {
    }
}
