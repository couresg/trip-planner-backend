package com.tripplanner.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LogMaskerTest {

    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "X-Owner-Token: 3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d          | X-Owner-Token: ****",
            "headers=[X-Owner-Token:\"3a7e4d21-0c55\", Accept:\"*/*\"]     | headers=[X-Owner-Token:\"****\", Accept:\"*/*\"]",
            "{\"tripId\":\"t1\",\"ownerToken\":\"3a7e4d21-0c55\"}           | {\"tripId\":\"t1\",\"ownerToken\":\"****\"}",
            "x-rapidapi-key=abcDEF123456                                | x-rapidapi-key=****",
            "X-RapidAPI-Key: abcDEF123456                               | X-RapidAPI-Key: ****",
            "GET https://maps.googleapis.com/x?key=AIzaSyXYZ&q=osaka    | GET https://maps.googleapis.com/x?key=****&q=osaka",
            "jdbc:mysql://h/db?user=trip&password=s3cr3t!&useSSL=false  | jdbc:mysql://h/db?user=trip&password=****&useSSL=false",
            "apiKey=abc, apiHost=aerodatabox.p.rapidapi.com             | apiKey=****, apiHost=aerodatabox.p.rapidapi.com",
            "X-Owner-Token: tokA,tokB,tokC                              | X-Owner-Token: ****",
            "headers=[X-Owner-Token:\"tokA,tokB\", Accept:\"*/*\"]       | headers=[X-Owner-Token:\"****\", Accept:\"*/*\"]",
            "parameters={key:[AIzaSyXYZ], q:[osaka]}                    | parameters={key:[****], q:[osaka]}",
            "parameters={password:[s3cr3t], token:[abc]}                | parameters={password:[****], token:[****]}",
            "key: AIzaSyXYZ                                             | key: ****",
            "Authorization: Bearer eyJhbGciOi.payload.sig               | Authorization: Bearer ****",
            "authorization=Basic dXNlcjpwYXNz                           | authorization=Basic ****",
            "callback?token=abc123&state=x                              | callback?token=****&state=x",
    })
    @DisplayName("이름 뒤의 비밀값만 ****로 가린다")
    void masksNamedSecrets(String input, String expected) {
        assertThat(LogMasker.mask(input.strip())).isEqualTo(expected.strip());
    }

    @Test
    @DisplayName("비밀값이 아닌 값(편명·날짜·여행 ID·monkey= 같은 이름)은 그대로 둔다")
    void keepsOrdinaryValues() {
        String text = "항공편 공급자 장애: flightNo=KE723, date=2026-11-05, reason=HTTP 500, staleCache=true, "
                + "tripId=9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01, monkey=banana, apiHost=a.b, "
                + "parameters={flightNo:[KE723], monkey:[x]}, tokenizer=on";
        assertThat(LogMasker.mask(text)).isEqualTo(text);
        assertThat(LogMasker.mask(null)).isNull();
        assertThat(LogMasker.mask("")).isEmpty();
    }

    @Test
    @DisplayName("logback 변환기가 메시지 인자와 예외 메시지(원인 포함)를 가린다")
    void convertersMaskMessageAndThrowable() {
        LoggerContext context = new LoggerContext();
        Logger logger = context.getLogger("test");
        IllegalStateException error = new IllegalStateException("요청 실패 password=hunter2",
                new RuntimeException("X-Owner-Token: " + TOKEN));
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.WARN, "요청 헤더 X-Owner-Token={}",
                error, new Object[] {TOKEN});

        MaskingMessageConverter message = new MaskingMessageConverter();
        MaskingThrowableProxyConverter throwable = new MaskingThrowableProxyConverter();
        throwable.setContext(context);
        throwable.start();

        assertThat(message.convert(event)).isEqualTo("요청 헤더 X-Owner-Token=****");
        String stack = throwable.convert(event);
        assertThat(stack).contains("password=****", "X-Owner-Token: ****", "IllegalStateException")
                .doesNotContain("hunter2", TOKEN);
    }
}
