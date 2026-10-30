package com.tripplanner.transfer.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공항 ↔ 숙소 교통편 계산 설정.
 *
 * @param arrivalWindowMin    도착일 조회 범위 (도착 기준 시각부터, 분)
 * @param immigrationOptions  선택 가능한 입국 소요 시간 (분)
 * @param defaultImmigrationMin 기본 입국 소요 시간 (분)
 */
@ConfigurationProperties(prefix = "app.transfer")
public record TransferProperties(Integer arrivalWindowMin, List<Integer> immigrationOptions,
        Integer defaultImmigrationMin) {

    public TransferProperties {
        arrivalWindowMin = arrivalWindowMin == null ? 120 : arrivalWindowMin;
        immigrationOptions = immigrationOptions == null || immigrationOptions.isEmpty()
                ? List.of(30, 60, 90)
                : List.copyOf(immigrationOptions);
        defaultImmigrationMin = defaultImmigrationMin == null ? 60 : defaultImmigrationMin;
    }

    public static TransferProperties defaults() {
        return new TransferProperties(null, null, null);
    }
}
