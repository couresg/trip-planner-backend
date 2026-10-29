package com.tripplanner.trip.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OwnerTokenServiceTest {

    private final OwnerTokenService service = new OwnerTokenService();

    @Test
    @DisplayName("발급한 토큰은 매번 다른 UUID 문자열이다")
    void issueRandomUuid() {
        String first = service.issue();
        String second = service.issue();

        assertThat(UUID.fromString(first).toString()).isEqualTo(first);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("토큰 해시는 SHA-256 소문자 hex 64자다")
    void hashIsSha256Hex() {
        assertThat(service.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("같은 토큰이면 해시가 일치하고, 다른 토큰·빈 토큰·null은 일치하지 않는다")
    void matches() {
        String token = service.issue();
        String hash = service.hash(token);

        assertThat(service.matches(token, hash)).isTrue();
        assertThat(service.matches(service.issue(), hash)).isFalse();
        assertThat(service.matches("", hash)).isFalse();
        assertThat(service.matches(null, hash)).isFalse();
    }
}
