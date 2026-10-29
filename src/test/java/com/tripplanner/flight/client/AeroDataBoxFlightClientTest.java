package com.tripplanner.flight.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** MockRestServiceServer로 HTTP 계층만 대체한다. 실제 AeroDataBox는 호출하지 않는다. */
class AeroDataBoxFlightClientTest {

    private static final String URL =
            "https://adb.test/flights/number/KE723/2026-11-05?dateLocalRole=Departure";
    private static final FlightNumber KE723 = FlightNumber.of("KE723");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);

    private MockRestServiceServer server;
    private AeroDataBoxFlightClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AeroDataBoxProperties properties = new AeroDataBoxProperties("test-key", "adb-host", "https://adb.test", null, null);
        client = new AeroDataBoxFlightClient(builder, properties, new AeroDataBoxParser(JsonMapper.builder().build()));
    }

    @Test
    @DisplayName("출발지 현지 날짜로 조회하고 RapidAPI 키·호스트를 헤더로 보낸 뒤 응답을 구간으로 변환한다")
    void success() throws IOException {
        String body = new ClassPathResource("fixtures/aerodatabox/KE723.json").getContentAsString(StandardCharsets.UTF_8);
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-RapidAPI-Key", "test-key"))
                .andExpect(header("X-RapidAPI-Host", "adb-host"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<FlightLeg> legs = client.findLegs(KE723, DATE);

        assertThat(legs).hasSize(1);
        assertThat(legs.getFirst().arrival().iata()).isEqualTo("KIX");
        server.verify();
    }

    @Test
    @DisplayName("204·404·빈 배열은 찾지 못함(빈 목록)이다")
    void notFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL)).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(client.findLegs(KE723, DATE)).isEmpty();
        assertThat(client.findLegs(KE723, DATE)).isEmpty();
        assertThat(client.findLegs(KE723, DATE)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("400(조회 가능 기간 밖 날짜·거부된 편명)은 장애가 아니라 찾지 못함(빈 목록)이다")
    void badRequestIsNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"message\":\"date is out of allowed range\"}").contentType(MediaType.APPLICATION_JSON));

        assertThat(client.findLegs(KE723, DATE)).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("API 키가 비어 있으면 클라이언트 생성(기동)에 실패한다")
    void blankKeyFails() {
        AeroDataBoxProperties properties = new AeroDataBoxProperties("  ", null, null, null, null);

        assertThatThrownBy(() -> new AeroDataBoxFlightClient(properties, new AeroDataBoxParser(JsonMapper.builder().build())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AERODATABOX_API_KEY");
    }

    @ParameterizedTest(name = "HTTP {0}")
    @ValueSource(ints = {401, 403, 429, 500, 503})
    @DisplayName("한도 초과·인증 실패·서버 오류는 공급자 장애 예외로 바꾼다")
    void providerError(int status) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.valueOf(status)));

        assertThatThrownBy(() -> client.findLegs(KE723, DATE))
                .isInstanceOf(FlightProviderException.class)
                .hasMessageNotContaining("test-key");
    }

    @Test
    @DisplayName("네트워크 오류(타임아웃 등)는 공급자 장애 예외로 바꾼다")
    void ioError() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> client.findLegs(KE723, DATE)).isInstanceOf(FlightProviderException.class);
    }

    @Test
    @DisplayName("200이지만 본문이 JSON 배열이 아니면 공급자 장애 예외로 바꾼다")
    void malformedBody() {
        server.expect(requestTo(URL)).andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.findLegs(KE723, DATE)).isInstanceOf(FlightProviderException.class);
    }

    @Test
    @DisplayName("설정값을 출력해도 API 키는 가려진다")
    void propertiesHideKey() {
        AeroDataBoxProperties properties = new AeroDataBoxProperties("secret-key", null, null, null, null);

        assertThat(properties.toString()).doesNotContain("secret-key").contains("***");
        assertThat(properties.apiHost()).isEqualTo("aerodatabox.p.rapidapi.com");
        assertThat(properties.baseUrl()).isEqualTo("https://aerodatabox.p.rapidapi.com");
    }
}
