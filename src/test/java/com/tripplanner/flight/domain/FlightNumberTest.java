package com.tripplanner.flight.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FlightNumberTest {

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "KE723|KE723",
            "ke 723|KE723",
            "  ke723  |KE723",
            "KE-723|KE723",
            "k e 7 2 3|KE723",
            "7c1302|7C1302",
            "OZ1|OZ1",
            "KE0723|KE723",
            "KE0007|KE7",
            "KE0|KE0"})
    @DisplayName("공백·하이픈을 지우고 대문자로 바꾸며, 숫자부의 앞자리 0은 제거한다")
    void normalize(String raw, String expected) {
        assertThat(FlightNumber.of(raw).value()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "KE", "K", "KEA723","KE12345", "KE72A", "KÉ723", "KE_723", "../KE723", "KE7.23"})
    @DisplayName("형식에 맞지 않는 편명은 INVALID_FLIGHT_NO 예외를 던진다")
    void invalid(String raw) {
        assertThatThrownBy(() -> FlightNumber.of(raw))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_FLIGHT_NO);
    }

    @Test
    @DisplayName("생성자에 정규화되지 않은 값을 직접 넣으면 거부한다")
    void constructorValidates() {
        assertThatThrownBy(() -> new FlightNumber("ke723")).isInstanceOf(BusinessException.class);
        assertThat(new FlightNumber("KE723")).isEqualTo(FlightNumber.of("ke 723"));
    }
}
