package com.tripplanner.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IP 기준 요청 제한. general은 /api/v1 전체, external은 외부 항공 API를 부를 수 있는 엔드포인트(항공편 조회·여행 생성)와 장소 추천에
 * 추가로 적용한다. cacheMaxSize는 IP별 버킷을 보관하는 최대 개수(메모리 상한)다.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(Boolean enabled, Integer generalPerMinute, Integer externalPerMinute,
        Long cacheMaxSize) {

    public RateLimitProperties {
        enabled = enabled == null || enabled;
        generalPerMinute = positive(generalPerMinute, 120, "general-per-minute");
        externalPerMinute = positive(externalPerMinute, 20, "external-per-minute");
        cacheMaxSize = cacheMaxSize == null ? 100_000L : cacheMaxSize;
        if (cacheMaxSize < 1) {
            throw new IllegalArgumentException("app.rate-limit.cache-max-size는 1 이상이어야 합니다.");
        }
    }

    private static int positive(Integer value, int defaultValue, String name) {
        int result = value == null ? defaultValue : value;
        if (result < 1) {
            throw new IllegalArgumentException("app.rate-limit." + name + "는 1 이상이어야 합니다.");
        }
        return result;
    }
}
