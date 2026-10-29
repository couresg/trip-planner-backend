package com.tripplanner.trip.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 익명 여행 소유 토큰. 원문(UUID, SecureRandom 기반)은 생성 응답에서 한 번만 내려주고, 서버에는 SHA-256 hex만 저장한다.
 * 토큰 원문은 로그에 남기지 않는다.
 */
@Component
public class OwnerTokenService {

    public String issue() {
        return UUID.randomUUID().toString();
    }

    public String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    /** 해시 비교는 상수 시간(MessageDigest.isEqual)으로 한다. */
    public boolean matches(String token, String storedHash) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),
                storedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
