package com.tripplanner.flight.client;

import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * AeroDataBox(RapidAPI) 실제 호출. external.mock=false일 때만 등록된다.
 * 204·400·404·빈 배열은 "찾지 못함"(빈 목록), 그 외 비정상 응답·네트워크 오류는 FlightProviderException.
 * API 키는 헤더로만 보내고 로그에 남기지 않는다. 키가 비어 있으면 기동 시 실패한다.
 */
@Component
@ConditionalOnProperty(name = "external.mock", havingValue = "false")
@EnableConfigurationProperties(AeroDataBoxProperties.class)
public class AeroDataBoxFlightClient implements FlightClient {

    /** dateLocalRole=Departure: 날짜를 출발지 현지 날짜로만 해석한다 (기본값 Both는 도착 날짜도 매칭). */
    private static final String LOOKUP_URI = "/flights/number/{flightNo}/{dateLocal}?dateLocalRole=Departure";

    private final RestClient restClient;
    private final AeroDataBoxParser parser;

    @Autowired
    public AeroDataBoxFlightClient(AeroDataBoxProperties properties, AeroDataBoxParser parser) {
        this(RestClient.builder().requestFactory(requestFactory(properties)), properties, parser);
    }

    /** 테스트에서 MockRestServiceServer를 바인딩한 builder를 넘길 수 있도록 분리한다. */
    AeroDataBoxFlightClient(RestClient.Builder builder, AeroDataBoxProperties properties, AeroDataBoxParser parser) {
        if (properties.apiKey().isBlank()) {
            throw new IllegalStateException("external.mock=false이면 AERODATABOX_API_KEY 환경변수가 필요합니다.");
        }
        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-RapidAPI-Key", properties.apiKey())
                .defaultHeader("X-RapidAPI-Host", properties.apiHost())
                .build();
        this.parser = parser;
    }

    @Override
    public List<FlightLeg> findLegs(FlightNumber flightNumber, LocalDate departureLocalDate) {
        try {
            return restClient.get()
                    .uri(LOOKUP_URI, flightNumber.value(), departureLocalDate.toString())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        // 400: 조회 가능 기간 밖의 날짜·공급자가 거부한 편명 → 찾지 못함으로 본다.
                        if (status == 204 || status == 400 || status == 404) {
                            return List.<FlightLeg>of();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new FlightProviderException("AeroDataBox HTTP " + status, null);
                        }
                        String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        return body.isBlank() ? List.<FlightLeg>of() : parser.parse(body);
                    });
        } catch (RestClientException e) {
            throw new FlightProviderException("AeroDataBox 호출 실패: " + e.getClass().getSimpleName(), e);
        }
    }

    private static JdkClientHttpRequestFactory requestFactory(AeroDataBoxProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
