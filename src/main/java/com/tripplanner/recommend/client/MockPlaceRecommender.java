package com.tripplanner.recommend.client;

import com.tripplanner.place.domain.PlaceCategory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * app.recommend.provider=mock(기본)일 때 쓰는 키 없는 규칙 기반 추천기. 같은 입력이면 항상 같은 결과다.
 * 관심 카테고리 장소를 enum 순서로 하나씩 번갈아 고르고(카테고리 안에서는 id 순), 남는 자리는 관심 밖 장소를 id 순으로 채운다.
 */
@Component
@ConditionalOnProperty(name = "app.recommend.provider", havingValue = "mock", matchIfMissing = true)
public class MockPlaceRecommender implements PlaceRecommender {

    @Override
    public List<Pick> recommend(Query query) {
        List<Iterator<Candidate>> byCategory = new ArrayList<>();
        for (PlaceCategory category : PlaceCategory.values()) {
            if (query.categories().contains(category)) {
                byCategory.add(query.candidates().stream()
                        .filter(c -> c.category() == category)
                        .sorted((a, b) -> Long.compare(a.id(), b.id()))
                        .iterator());
            }
        }

        List<Pick> picks = new ArrayList<>();
        boolean picked = true;
        while (picks.size() < query.count() && picked) {
            picked = false;
            for (Iterator<Candidate> it : byCategory) {
                if (picks.size() < query.count() && it.hasNext()) {
                    Candidate c = it.next();
                    picks.add(new Pick(c.id(), c.category().labelKo() + " 관심사와 맞아요 · " + c.facts()));
                    picked = true;
                }
            }
        }
        query.candidates().stream()
                .filter(c -> !query.categories().contains(c.category()))
                .sorted((a, b) -> Long.compare(a.id(), b.id()))
                .limit(Math.max(0, query.count() - picks.size()))
                .forEach(c -> picks.add(new Pick(c.id(),
                        "관심 카테고리 밖(" + c.category().labelKo() + ")이지만 일정을 채울 후보예요 · " + c.facts())));
        return picks;
    }
}
