package com.tripplanner.recommend.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.itinerary.repository.ItineraryItemRepository;
import com.tripplanner.place.domain.Place;
import com.tripplanner.place.domain.PlaceCategory;
import com.tripplanner.place.repository.PlaceRepository;
import com.tripplanner.place.service.PlaceService;
import com.tripplanner.recommend.api.PlaceRecommendationResponse;
import com.tripplanner.recommend.client.PlaceRecommender;
import com.tripplanner.recommend.client.PlaceRecommender.Candidate;
import com.tripplanner.recommend.client.PlaceRecommender.Pick;
import com.tripplanner.recommend.domain.Pace;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.service.TripService;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 장소 추천 받기 (PRD F4-1). 후보는 여행 도시의 장소 중 어느 날짜에든 일정에 없는 장소다.
 * 추천기 결과는 어떤 구현이든 여기서 검증한다: 후보 밖·null·중복 ID는 버리고, 개수로 자르고, 순위는 1부터 다시 매긴다.
 * 추천기 호출은 트랜잭션 밖에서 한다 (다음 단계 LLM 응답을 기다리는 동안 DB 커넥션을 잡지 않도록).
 */
@Service
@EnableConfigurationProperties(RecommendProperties.class)
public class PlaceRecommendationService {

    /** 이유 최대 길이 (유니코드 코드 포인트) */
    static final int REASON_MAX_LENGTH = 200;
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private static final Logger log = LoggerFactory.getLogger(PlaceRecommendationService.class);

    private final TripService tripService;
    private final PlaceRepository placeRepository;
    private final ItineraryItemRepository itemRepository;
    private final PlaceService placeService;
    private final PlaceRecommender recommender;
    private final TransactionTemplate readOnlyTx;
    /** 실패(예외)는 저장되지 않는다. 키에 후보 ID 목록이 있어 일정이 바뀌면 새 키가 된다. */
    private final Cache<CacheKey, List<Pick>> cache;

    public PlaceRecommendationService(TripService tripService, PlaceRepository placeRepository,
            ItineraryItemRepository itemRepository, PlaceService placeService, PlaceRecommender recommender,
            PlatformTransactionManager transactionManager, RecommendProperties properties) {
        this.tripService = tripService;
        this.placeRepository = placeRepository;
        this.itemRepository = itemRepository;
        this.placeService = placeService;
        this.recommender = recommender;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cacheMaxSize())
                .build();
    }

    /** @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP, RECOMMENDATION_UNAVAILABLE */
    public PlaceRecommendationResponse recommend(String tripId, String ownerToken, Set<PlaceCategory> categories,
            Pace pace) {
        Loaded loaded = readOnlyTx.execute(status -> load(tripId, ownerToken));
        Set<PlaceCategory> interests = EnumSet.copyOf(categories);
        Map<Long, Place> candidates = loaded.candidates();
        int count = Math.min(loaded.dayCount() * pace.placesPerDay(), candidates.size());

        List<Pick> picks = List.of();
        if (count > 0) {
            CacheKey key = new CacheKey(loaded.cityId(), List.copyOf(candidates.keySet()), interests, pace, count);
            List<Candidate> facts = candidates.values().stream().map(PlaceRecommendationService::candidate).toList();
            // cache.get(key, fn)은 계산 중 같은 해시 구간의 다른 키를 막으므로 추천기(다음 단계: 느린 LLM)를 그 안에서 부르지 않는다.
            // 같은 키의 동시 요청은 추천기를 중복으로 부를 수 있고 나중 결과가 남는다.
            picks = cache.getIfPresent(key);
            if (picks == null) {
                picks = sanitize(callRecommender(new PlaceRecommender.Query(interests, pace, count, facts)),
                        candidates, count);
                cache.put(key, picks);
            }
        }

        List<PlaceRecommendationResponse.Recommendation> recommendations = new ArrayList<>();
        for (Pick pick : picks) {
            recommendations.add(new PlaceRecommendationResponse.Recommendation(recommendations.size() + 1,
                    pick.reason(), placeService.toResponse(candidates.get(pick.placeId()))));
        }
        return new PlaceRecommendationResponse(pace, pace.placesPerDay(), loaded.dayCount(), candidates.size(),
                recommendations);
    }

    private Loaded load(String tripId, String ownerToken) {
        Trip trip = tripService.findOwned(tripId, ownerToken);
        Set<Long> scheduled = new HashSet<>(itemRepository.findPlaceIdsByTrip(trip));
        Map<Long, Place> candidates = new LinkedHashMap<>();
        for (Place place : placeRepository.search(trip.getCity().getId(), null, null)) {
            if (!scheduled.contains(place.getId())) {
                candidates.put(place.getId(), place);
            }
        }
        int dayCount = (int) ChronoUnit.DAYS.between(trip.getStartDate(), trip.getEndDate()) + 1;
        return new Loaded(trip.getCity().getId(), dayCount, candidates);
    }

    private List<Pick> callRecommender(PlaceRecommender.Query query) {
        try {
            List<Pick> picks = recommender.recommend(query);
            return picks == null ? List.of() : picks;
        } catch (RuntimeException e) {
            log.warn("장소 추천기 호출 실패", e);
            throw new BusinessException(ErrorCode.RECOMMENDATION_UNAVAILABLE,
                    "지금은 장소를 추천할 수 없습니다. 잠시 후 다시 시도하세요.");
        }
    }

    /** 후보에 없는 ID·null·중복(처음 것만 남김)을 버리고 count개까지 남긴다. 이유는 정리하고 비어 있으면 시드 사실로 채운다. */
    static List<Pick> sanitize(List<Pick> picks, Map<Long, Place> candidates, int count) {
        Set<Long> seen = new HashSet<>();
        List<Pick> result = new ArrayList<>();
        for (Pick pick : picks) {
            if (result.size() >= count) {
                break;
            }
            if (pick == null || pick.placeId() == null || !candidates.containsKey(pick.placeId())
                    || !seen.add(pick.placeId())) {
                continue;
            }
            result.add(new Pick(pick.placeId(), reason(pick.reason(), candidates.get(pick.placeId()))));
        }
        return List.copyOf(result);
    }

    /**
     * 제어 문자(줄바꿈·탭 포함)는 공백으로, 연속 공백은 하나로 바꾸고 앞뒤를 지운 뒤 코드 포인트 기준으로 자른다
     * (UTF-16 길이로 자르면 이모지 같은 서로게이트 쌍이 깨진다).
     */
    static String reason(String raw, Place place) {
        String reason = raw == null ? "" : CONTROL.matcher(raw).replaceAll(" ");
        reason = SPACES.matcher(reason).replaceAll(" ").strip();
        if (reason.isEmpty()) {
            Candidate c = candidate(place);
            reason = c.category().labelKo() + " · " + c.facts();
        }
        if (reason.codePointCount(0, reason.length()) > REASON_MAX_LENGTH) {
            reason = reason.substring(0, reason.offsetByCodePoints(0, REASON_MAX_LENGTH)).strip();
        }
        return reason;
    }

    private static Candidate candidate(Place p) {
        return new Candidate(p.getId(), p.getNameKo(), p.getCategory(), p.getSummary(), p.getDurationMin(),
                p.isAlwaysOpen());
    }

    /** candidates: id 순 (PlaceRepository.search 정렬) */
    private record Loaded(long cityId, int dayCount, Map<Long, Place> candidates) {
    }

    private record CacheKey(long cityId, List<Long> candidateIds, Set<PlaceCategory> categories, Pace pace,
            int count) {
    }
}
