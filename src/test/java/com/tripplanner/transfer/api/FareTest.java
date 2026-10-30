package com.tripplanner.transfer.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.transfer.api.ArrivalTransferResponse.Fare;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FareTest {

    @Test
    @DisplayName("최소·최대가 같으면 고정 요금, 다르면 범위, 둘 다 없으면 null")
    void variants() {
        assertThat(Fare.of(1490, 1490, "JPY")).isEqualTo(new Fare(1490, null, null, "JPY", "¥1,490"));
        assertThat(Fare.of(17000, 20000, "JPY"))
                .isEqualTo(new Fare(null, 17000, 20000, "JPY", "¥17,000~20,000"));
        assertThat(Fare.of(null, 900, "JPY")).isEqualTo(new Fare(900, null, null, "JPY", "¥900"));
        assertThat(Fare.of(null, null, "JPY")).isNull();
    }

    @Test
    @DisplayName("통화 기호: JPY ¥, KRW ₩, USD $, 그 외는 코드 + 공백")
    void currencySymbol() {
        assertThat(Fare.of(15000, 15000, "KRW").display()).isEqualTo("₩15,000");
        assertThat(Fare.of(12, 12, "USD").display()).isEqualTo("$12");
        assertThat(Fare.of(1200, 1500, "TWD").display()).isEqualTo("TWD 1,200~1,500");
    }
}
