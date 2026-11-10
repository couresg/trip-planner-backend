package com.tripplanner.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.common.config.RateLimitProperties;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import io.github.bucket4j.TimeMeter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** 고정 시간(TimeMeter)으로 버킷 소비·충전과 Retry-After 계산을 검증한다. 응답 본문 형식은 RateLimitMvcTest가 본다. */
class RateLimitFilterTest {

    private final FakeTimeMeter time = new FakeTimeMeter();
    private final List<Exception> resolved = new ArrayList<>();
    private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
        resolved.add(ex);
        response.setStatus(429);
        return new ModelAndView();
    };

    @Test
    @DisplayName("external 한도를 넘으면 429와 다음 토큰까지 남은 초(올림)를 Retry-After로 준다")
    void externalLimitRetryAfter() throws Exception {
        RateLimitFilter filter = filter(120, 2);

        assertThat(call(filter, "GET", "/api/v1/flights/lookup").getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/trips").getStatus()).isEqualTo(200);
        MockHttpServletResponse rejected = call(filter, "GET", "/api/v1/flights/lookup");

        // 2/분 = 30초마다 1개
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("30");
        assertThat(resolved).singleElement().satisfies(ex -> {
            assertThat(ex).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
        });
    }

    @Test
    @DisplayName("남은 대기가 1초 미만이어도 Retry-After는 1이고, 대기 시간이 지나면 다시 허용된다")
    void refillAfterWait() throws Exception {
        RateLimitFilter filter = filter(120, 2);
        call(filter, "GET", "/api/v1/flights/lookup");
        call(filter, "GET", "/api/v1/flights/lookup");

        time.advance(Duration.ofMillis(29_500));
        MockHttpServletResponse almost = call(filter, "GET", "/api/v1/flights/lookup");
        assertThat(almost.getStatus()).isEqualTo(429);
        assertThat(almost.getHeader("Retry-After")).isEqualTo("1");

        time.advance(Duration.ofMillis(500));
        assertThat(call(filter, "GET", "/api/v1/flights/lookup").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/flights/lookup").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("external 요청은 general도 소비하고, general이 거절하면 external 토큰을 돌려준다")
    void externalConsumesGeneralAndRefunds() throws Exception {
        // general 2/분(30초마다 1개), external 1/분
        RateLimitFilter filter = filter(2, 1);
        call(filter, "GET", "/api/v1/cities");
        call(filter, "GET", "/api/v1/cities");

        MockHttpServletResponse rejected = call(filter, "POST", "/api/v1/trips");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("30");

        // 돌려받지 못했다면 external은 30초 뒤 0.5개라 거절된다.
        time.advance(Duration.ofSeconds(30));
        assertThat(call(filter, "POST", "/api/v1/trips").getStatus()).isEqualTo(200);
        // 이제 general 0개
        assertThat(call(filter, "GET", "/api/v1/cities").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("general 한도는 외부 호출이 아닌 /api/v1 요청에도 적용된다")
    void generalLimit() throws Exception {
        RateLimitFilter filter = filter(3, 20);
        for (int i = 0; i < 3; i++) {
            assertThat(call(filter, "GET", "/api/v1/trips/abc/itinerary").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse rejected = call(filter, "GET", "/api/v1/cities");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("20");
    }

    @Test
    @DisplayName("IP마다 버킷이 따로이고 X-Forwarded-For는 무시한다")
    void perIpIgnoringForwardedFor() throws Exception {
        RateLimitFilter filter = filter(120, 1);
        call(filter, "GET", "/api/v1/flights/lookup");

        MockHttpServletRequest spoofed = request("GET", "/api/v1/flights/lookup");
        spoofed.addHeader("X-Forwarded-For", "203.0.113.9");
        assertThat(call(filter, spoofed).getStatus()).isEqualTo(429);

        MockHttpServletRequest other = request("GET", "/api/v1/flights/lookup");
        other.setRemoteAddr("10.0.0.2");
        assertThat(call(filter, other).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("HEAD 항공편 조회도 GET과 같이 external 한도를 소비한다")
    void headCountsAsExternal() throws Exception {
        RateLimitFilter filter = filter(120, 1);
        assertThat(call(filter, "HEAD", "/api/v1/flights/lookup").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/flights/lookup").getStatus()).isEqualTo(429);
        assertThat(call(filter, "HEAD", "/api/v1/flights/lookup").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("장소 추천 GET은 external 한도를 소비하고, 일정 조회처럼 다른 여행 경로는 소비하지 않는다")
    void recommendationCountsAsExternal() throws Exception {
        RateLimitFilter filter = filter(120, 1);
        String trip = "/api/v1/trips/9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
        assertThat(call(filter, "GET", trip + "/recommendations/places").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", trip + "/itinerary").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", trip + "/recommendations/places/extra").getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", trip + "/recommendations/places").getStatus()).isEqualTo(429);
        assertThat(call(filter, "GET", "/api/v1/flights/lookup").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("IPv6는 /64 대역으로 묶고, IPv4와 IPv4-mapped IPv6는 같은 IPv4 키를 쓴다")
    void clientKey() throws Exception {
        assertThat(RateLimitFilter.clientKey("203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(RateLimitFilter.clientKey("::ffff:203.0.113.9")).isEqualTo("203.0.113.9");
        assertThat(RateLimitFilter.clientKey("2001:db8:1:2:aaaa::1"))
                .isEqualTo(RateLimitFilter.clientKey("2001:0db8:0001:0002:ffff:ffff:ffff:ffff"))
                .isEqualTo("20010db800010002::/64");
        assertThat(RateLimitFilter.clientKey("2001:db8:1:3::1")).isNotEqualTo(RateLimitFilter.clientKey("2001:db8:1:2::1"));

        RateLimitFilter filter = filter(120, 1);
        MockHttpServletRequest first = request("GET", "/api/v1/flights/lookup");
        first.setRemoteAddr("2001:db8:1:2::1");
        assertThat(call(filter, first).getStatus()).isEqualTo(200);
        MockHttpServletRequest sameSubnet = request("GET", "/api/v1/flights/lookup");
        sameSubnet.setRemoteAddr("2001:db8:1:2:dead:beef:0:7");
        assertThat(call(filter, sameSubnet).getStatus()).isEqualTo(429);
        MockHttpServletRequest mapped = request("GET", "/api/v1/flights/lookup");
        mapped.setRemoteAddr("::ffff:10.0.0.1");
        assertThat(call(filter, mapped).getStatus()).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/flights/lookup").getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("예외 resolver가 처리하지 못하면(null) sendError(429)로 응답하고 Retry-After는 유지한다")
    void fallbackWhenUnresolved() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(true, 120, 1, 10L),
                (request, response, handler, ex) -> null, time);
        call(filter, "GET", "/api/v1/flights/lookup");

        MockHttpServletResponse rejected = call(filter, "GET", "/api/v1/flights/lookup");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.isCommitted()).isTrue();
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
    }

    @Test
    @DisplayName("헬스체크·OPTIONS·/api/v1 밖 경로는 제한하지 않는다")
    void skipped() throws Exception {
        RateLimitFilter filter = filter(1, 1);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/api/v1/health").getStatus()).isEqualTo(200);
            assertThat(call(filter, "OPTIONS", "/api/v1/trips").getStatus()).isEqualTo(200);
            assertThat(call(filter, "GET", "/swagger-ui.html").getStatus()).isEqualTo(200);
        }
        assertThat(call(filter, "POST", "/api/v1/trips").getStatus()).isEqualTo(200);
        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("enabled=false이면 제한하지 않는다")
    void disabled() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(false, 1, 1, 10L), resolver, time);
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "POST", "/api/v1/trips").getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("버킷 캐시가 가득 차도 새 IP 요청은 처리된다")
    void boundedCache() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new RateLimitProperties(true, 1, 1, 2L), resolver, time);
        for (int i = 0; i < 50; i++) {
            MockHttpServletRequest request = request("GET", "/api/v1/cities");
            request.setRemoteAddr("10.0.1." + i);
            assertThat(call(filter, request).getStatus()).isEqualTo(200);
        }
    }

    private RateLimitFilter filter(int generalPerMinute, int externalPerMinute) {
        return new RateLimitFilter(new RateLimitProperties(true, generalPerMinute, externalPerMinute, 1000L), resolver,
                time);
    }

    private static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String method, String path) throws Exception {
        return call(filter, request(method, path));
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private static final class FakeTimeMeter implements TimeMeter {

        private long nanos;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long currentTimeNanos() {
            return nanos;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    }
}
