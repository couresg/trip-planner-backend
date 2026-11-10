package com.tripplanner.common.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tripplanner.common.config.RateLimitProperties;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * /api/v1 요청을 클라이언트 IP(remoteAddr) 기준으로 제한한다. 외부 항공 API를 부를 수 있는 요청과 장소 추천은 general과 external
 * 두 버킷을 모두 소비한다. 한도를 넘으면 컨트롤러를 호출하지 않고 RATE_LIMITED(429) + Retry-After(초)로 응답한다.
 * 응답 본문은 GlobalExceptionHandler가 만들도록 HandlerExceptionResolver에 넘겨 다른 오류와 같은 형식을 유지한다.
 * X-Forwarded-For는 위조할 수 있어 믿지 않는다(프록시 뒤 배포는 백로그, docs/PRD.md 미결 사항).
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/v1/";
    private static final String HEALTH_PATH = "/api/v1/health";
    private static final Duration PERIOD = Duration.ofMinutes(1);
    /** 장소 추천은 지금은 Mock이지만 다음 단계 LLM 호출에 비용이 들어 external로 센다. */
    private static final Pattern RECOMMENDATION_PATH = Pattern.compile("/api/v1/trips/[^/]+/recommendations/places");

    private final RateLimitProperties properties;
    private final HandlerExceptionResolver exceptionResolver;
    private final TimeMeter timeMeter;
    /** 버킷은 1분이면 가득 차므로 그보다 오래 쓰지 않은 IP는 버려도 결과가 같다. */
    private final Cache<String, IpBuckets> buckets;

    @Autowired
    public RateLimitFilter(RateLimitProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this(properties, exceptionResolver, TimeMeter.SYSTEM_NANOTIME);
    }

    /** 테스트에서 시간을 고정할 수 있도록 분리한다. */
    RateLimitFilter(RateLimitProperties properties, HandlerExceptionResolver exceptionResolver, TimeMeter timeMeter) {
        this.properties = properties;
        this.exceptionResolver = exceptionResolver;
        this.timeMeter = timeMeter;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(properties.cacheMaxSize())
                .expireAfterAccess(PERIOD)
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.enabled() || "OPTIONS".equals(request.getMethod())) {
            return true;
        }
        String path = path(request);
        return !path.startsWith(API_PREFIX) || path.equals(HEALTH_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        IpBuckets ip = buckets.get(clientKey(request.getRemoteAddr()), key -> new IpBuckets(
                newBucket(properties.generalPerMinute()), newBucket(properties.externalPerMinute())));

        long waitNanos = isExternal(request) ? consumeBoth(ip) : waitNanos(ip.general().tryConsumeAndReturnRemaining(1));
        if (waitNanos > 0) {
            reject(request, response, waitNanos);
            return;
        }
        chain.doFilter(request, response);
    }

    /** 더 엄격한 external을 먼저 소비하고, general이 거절하면 external 토큰을 돌려준다. */
    private static long consumeBoth(IpBuckets ip) {
        long externalWait = waitNanos(ip.external().tryConsumeAndReturnRemaining(1));
        if (externalWait > 0) {
            return externalWait;
        }
        long generalWait = waitNanos(ip.general().tryConsumeAndReturnRemaining(1));
        if (generalWait > 0) {
            ip.external().addTokens(1);
        }
        return generalWait;
    }

    private static long waitNanos(ConsumptionProbe probe) {
        return probe.isConsumed() ? 0 : Math.max(1, probe.getNanosToWaitForRefill());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long waitNanos) throws IOException {
        long retryAfterSeconds = Math.max(1, (waitNanos + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1));
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        ModelAndView resolved = exceptionResolver.resolveException(request, response, null, new BusinessException(
                ErrorCode.RATE_LIMITED, "요청이 너무 많습니다. " + retryAfterSeconds + "초 후에 다시 시도하세요."));
        if (resolved == null) {
            // 처리하는 resolver가 없으면 본문 없이라도 429를 보장한다 (Retry-After는 이미 붙어 있다).
            response.sendError(ErrorCode.RATE_LIMITED.status().value());
        }
    }

    /**
     * 버킷 키. IPv6는 한 사용자(회선)가 /64 대역 전체를 받는 경우가 많아 주소를 바꿔 가며 우회하지 못하도록 /64로 묶는다.
     * IPv4와 IPv4-mapped IPv6(::ffff:a.b.c.d)는 IPv4 주소 그대로 쓴다. remoteAddr는 IP 리터럴이라 DNS 조회는 없다.
     */
    static String clientKey(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.indexOf(':') < 0) {
            return remoteAddr;
        }
        try {
            InetAddress address = InetAddress.getByName(remoteAddr);
            if (address instanceof Inet6Address) {
                byte[] prefix = Arrays.copyOf(address.getAddress(), 8);
                return HexFormat.of().formatHex(prefix) + "::/64";
            }
            return address.getHostAddress();
        } catch (UnknownHostException e) {
            return remoteAddr;
        }
    }

    private Bucket newBucket(int perMinute) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, PERIOD))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private static boolean isExternal(HttpServletRequest request) {
        String path = path(request);
        String method = request.getMethod();
        // Spring MVC는 HEAD를 @GetMapping으로 처리하므로 GET과 같이 센다.
        boolean get = "GET".equals(method) || "HEAD".equals(method);
        return (get && (path.equals("/api/v1/flights/lookup") || RECOMMENDATION_PATH.matcher(path).matches()))
                || ("POST".equals(method) && path.equals("/api/v1/trips"));
    }

    /** 컨테이너가 디코딩·정규화한 경로(경로 파라미터 ;x 제거). requestURI를 그대로 쓰면 우회될 수 있다. */
    private static String path(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return request.getServletPath() + (pathInfo == null ? "" : pathInfo);
    }

    private record IpBuckets(Bucket general, Bucket external) {
    }
}
