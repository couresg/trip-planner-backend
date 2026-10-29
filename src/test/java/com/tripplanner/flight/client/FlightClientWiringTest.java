package com.tripplanner.flight.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** external.mock 값에 따라 FlightClient 구현이 하나만 등록되는지 확인한다. */
class FlightClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(AeroDataBoxParser.class, MockFlightClient.class, AeroDataBoxFlightClient.class);

    @Test
    @DisplayName("external.mock이 없으면 Mock 구현이 등록된다")
    void mockByDefault() {
        runner.run(context -> assertThat(context).getBean(FlightClient.class).isInstanceOf(MockFlightClient.class));
    }

    @Test
    @DisplayName("external.mock=true이면 API 키가 없어도 Mock 구현으로 기동한다")
    void mockWithoutKey() {
        runner.withPropertyValues("external.mock=true", "external.aerodatabox.api-key=")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(FlightClient.class).isInstanceOf(MockFlightClient.class));
    }

    @Test
    @DisplayName("external.mock=false인데 API 키가 비어 있으면 기동에 실패한다")
    void realWithoutKeyFails() {
        runner.withPropertyValues("external.mock=false", "external.aerodatabox.api-key=")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("AERODATABOX_API_KEY"));
    }

    @Test
    @DisplayName("external.mock=false이면 AeroDataBox 구현이 등록되고 설정값이 바인딩된다")
    void realWhenMockFalse() {
        runner.withPropertyValues("external.mock=false", "external.aerodatabox.api-key=k",
                        "external.aerodatabox.read-timeout=7s")
                .run(context -> {
                    assertThat(context).getBean(FlightClient.class).isInstanceOf(AeroDataBoxFlightClient.class);
                    assertThat(context.getBean(AeroDataBoxProperties.class).readTimeout()).isEqualTo(Duration.ofSeconds(7));
                });
    }
}
