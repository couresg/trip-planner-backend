package com.tripplanner.transfer.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripplanner.transfer.domain.TransportMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 도착일 교통편 (F2). 시각은 도착 공항 현지 오프셋으로 표현한다. */
public record ArrivalTransferResponse(
        OffsetDateTime baseTime,
        OffsetDateTime windowStart,
        OffsetDateTime windowEnd,
        OffsetDateTime readyAt,
        String timezone,
        boolean noTransitWarning,
        List<Option> options) {

    public record Option(
            long routeId,
            TransportMode mode,
            String name,
            String from,
            String to,
            int durationMin,
            int walkAfterMin,
            Fare fare,
            String boardingTip,
            boolean is24h,
            List<Departure> departures,
            OffsetDateTime nextFirstDeparture,
            LocalDate dataAsOf,
            String source,
            String note) {
    }

    /** 고정 요금이면 amount, 범위면 min·max만 채운다 (빈 필드는 직렬화하지 않음). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Fare(Integer amount, Integer min, Integer max, String currency, String display) {

        private static final Map<String, String> SYMBOLS = Map.of("JPY", "¥", "KRW", "₩", "USD", "$");

        /** 둘 다 null이면 null, 하나만 있거나 같으면 고정 요금, 다르면 범위. 기호가 없는 통화는 "CODE " 접두어. */
        public static Fare of(Integer fareMin, Integer fareMax, String currency) {
            if (fareMin == null && fareMax == null) {
                return null;
            }
            String prefix = SYMBOLS.getOrDefault(currency, currency + " ");
            if (fareMin == null || fareMax == null || fareMin.equals(fareMax)) {
                int amount = fareMin != null ? fareMin : fareMax;
                return new Fare(amount, null, null, currency, prefix + format(amount));
            }
            return new Fare(null, fareMin, fareMax, currency, prefix + format(fareMin) + "~" + format(fareMax));
        }

        private static String format(int value) {
            return NumberFormat.getIntegerInstance(Locale.US).format(value);
        }
    }

    public record Departure(OffsetDateTime departAt, OffsetDateTime arriveAreaAt, DepartureStatus status,
            boolean recommended) {
    }

    public enum DepartureStatus {
        AVAILABLE,
        HARD_TO_CATCH
    }
}
