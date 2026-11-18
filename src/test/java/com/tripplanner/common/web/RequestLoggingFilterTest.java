package com.tripplanner.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    @DisplayName("API 요청은 메서드·경로·상태를 한 줄로 남기고 쿼리 문자열과 소유 토큰 헤더는 남기지 않는다")
    void logsApiRequest(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/trips/abc/itinerary");
        request.setQueryString("secret=xyz");
        request.addHeader("X-Owner-Token", "owner-token-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(output).contains("POST /api/v1/trips/abc/itinerary -> 201 (");
        assertThat(output).doesNotContain("secret=xyz").doesNotContain("owner-token-value");
    }

    @Test
    @DisplayName("/api/ 밖의 요청(Swagger 등)은 남기지 않는다")
    void skipsNonApiRequest(CapturedOutput output) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/swagger-ui.html");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).doesNotContain("/swagger-ui.html");
    }
}
