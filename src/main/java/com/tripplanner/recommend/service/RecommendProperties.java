package com.tripplanner.recommend.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 장소 추천 설정. provider는 MockPlaceRecommender 등의 @ConditionalOnProperty가 읽는다(여기서는 기록용).
 *
 * @param cacheTtl     같은 입력의 추천 결과를 메모리에 두는 시간
 * @param cacheMaxSize 메모리 캐시 최대 개수
 */
@ConfigurationProperties(prefix = "app.recommend")
public record RecommendProperties(String provider, Duration cacheTtl, Long cacheMaxSize) {

    public RecommendProperties {
        provider = provider == null ? "mock" : provider;
        cacheTtl = cacheTtl == null ? Duration.ofHours(1) : cacheTtl;
        cacheMaxSize = cacheMaxSize == null ? 1_000L : cacheMaxSize;
    }

    public static RecommendProperties defaults() {
        return new RecommendProperties(null, null, null);
    }
}
