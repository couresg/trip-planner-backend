package com.tripplanner.transfer.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 공항 ↔ 숙소 교통편 계산 설정.
 *
 * @param arrivalWindowMin    도착일 조회 범위 (도착 기준 시각부터, 분)
 * @param immigrationOptions  선택 가능한 입국 소요 시간 (분)
 * @param defaultImmigrationMin 기본 입국 소요 시간 (분)
 * @param departureWindowMin  귀국일 조회 범위 (공항 도착 마감 시각까지, 분)
 * @param departureLookbackMin 범위 안에 편이 없는 노선을 되돌아볼 한계 (공항 도착 마감 시각부터, 분)
 * @param airportBufferOptions 선택 가능한 공항 도착 여유 (분)
 * @param defaultAirportBufferMin 기본 공항 도착 여유 (분)
 * @param boardingMarginMin   숙소 출발 시각에 더하는 승차 여유 (분)
 */
@ConfigurationProperties(prefix = "app.transfer")
public record TransferProperties(Integer arrivalWindowMin, List<Integer> immigrationOptions,
        Integer defaultImmigrationMin, Integer departureWindowMin, Integer departureLookbackMin,
        List<Integer> airportBufferOptions, Integer defaultAirportBufferMin, Integer boardingMarginMin) {

    public TransferProperties {
        arrivalWindowMin = arrivalWindowMin == null ? 120 : arrivalWindowMin;
        immigrationOptions = immigrationOptions == null || immigrationOptions.isEmpty()
                ? List.of(30, 60, 90)
                : List.copyOf(immigrationOptions);
        defaultImmigrationMin = defaultImmigrationMin == null ? 60 : defaultImmigrationMin;
        departureWindowMin = departureWindowMin == null ? 90 : departureWindowMin;
        departureLookbackMin = departureLookbackMin == null ? 240 : departureLookbackMin;
        airportBufferOptions = airportBufferOptions == null || airportBufferOptions.isEmpty()
                ? List.of(120, 150, 180)
                : List.copyOf(airportBufferOptions);
        defaultAirportBufferMin = defaultAirportBufferMin == null ? 120 : defaultAirportBufferMin;
        boardingMarginMin = boardingMarginMin == null ? 10 : boardingMarginMin;
    }

    public static TransferProperties defaults() {
        return new TransferProperties(null, null, null, null, null, null, null, null);
    }
}
