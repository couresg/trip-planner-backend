package com.tripplanner.itinerary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.transfer.api.ArrivalTransferResponse;
import com.tripplanner.transfer.api.ArrivalTransferResponse.DepartureStatus;
import com.tripplanner.transfer.api.DepartureTransferResponse;
import com.tripplanner.transfer.domain.TransportMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** earliestStart·latestEnd가 도착일 타임라인·귀국일 강조 문구와 같은 규칙인지 고정 값으로 검증한다. */
class ItineraryTimeBoundsTest {

    private static final OffsetDateTime READY_AT = time("2026-11-05T15:00:00+09:00");

    private static OffsetDateTime time(String iso) {
        return OffsetDateTime.parse(iso);
    }

    private static ArrivalTransferResponse arrival(List<ArrivalTransferResponse.Option> options) {
        return new ArrivalTransferResponse(READY_AT.minusHours(1), READY_AT.minusHours(1), READY_AT.plusHours(1),
                READY_AT, "Asia/Tokyo", false, options);
    }

    private static ArrivalTransferResponse.Option arrivalOption(TransportMode mode, int durationMin, int walkMin,
            ArrivalTransferResponse.Departure... departures) {
        return new ArrivalTransferResponse.Option(1L, mode, "노선", null, null, durationMin, walkMin, null, null, false,
                List.of(departures), null, LocalDate.of(2026, 11, 3), "SEED", null);
    }

    private static ArrivalTransferResponse.Departure train(String departAt, String arriveAt, boolean recommended) {
        return new ArrivalTransferResponse.Departure(time(departAt), time(arriveAt),
                recommended ? DepartureStatus.AVAILABLE : DepartureStatus.HARD_TO_CATCH, recommended);
    }

    private static DepartureTransferResponse departure(boolean noTime, OffsetDateTime latestLeaveAt,
            OffsetDateTime... leaveByAt) {
        List<DepartureTransferResponse.Option> options = Arrays.stream(leaveByAt)
                .map(leaveBy -> new DepartureTransferResponse.Option(1L, TransportMode.TAXI, "택시", null, null, 50, 0,
                        null, null, true, List.of(), leaveBy, LocalDate.of(2026, 11, 3), "SEED", null))
                .toList();
        OffsetDateTime flight = time("2026-11-09T18:00:00+09:00");
        return new DepartureTransferResponse(flight, null, flight.minusHours(2), flight.minusMinutes(210),
                "Asia/Tokyo", 10, latestLeaveAt == null, noTime, latestLeaveAt, options);
    }

    @Test
    @DisplayName("추천편이 여럿이면 숙소에 가장 먼저 도착하는 시각을 쓰고 추천이 아닌 편과 택시는 무시한다")
    void earliestRecommendedArrival() {
        ArrivalTransferResponse response = arrival(List.of(
                arrivalOption(TransportMode.RAIL, 40, 5,
                        train("2026-11-05T14:30:00+09:00", "2026-11-05T15:15:00+09:00", false),
                        train("2026-11-05T15:30:00+09:00", "2026-11-05T16:15:00+09:00", true)),
                arrivalOption(TransportMode.BUS, 50, 0,
                        train("2026-11-05T15:05:00+09:00", "2026-11-05T15:55:00+09:00", true)),
                arrivalOption(TransportMode.TAXI, 10, 0)));

        assertThat(ItineraryService.earliestStart(response)).isEqualTo(time("2026-11-05T15:55:00+09:00"));
    }

    @Test
    @DisplayName("추천편이 없으면 입국 완료 + 택시 소요 + 도보다")
    void readyAtPlusTaxi() {
        ArrivalTransferResponse response = arrival(List.of(
                arrivalOption(TransportMode.RAIL, 40, 5),
                arrivalOption(TransportMode.TAXI, 50, 3)));

        assertThat(ItineraryService.earliestStart(response)).isEqualTo(time("2026-11-05T15:53:00+09:00"));
    }

    @Test
    @DisplayName("추천편도 택시도 없으면 입국 완료 시각이다")
    void readyAtOnly() {
        ArrivalTransferResponse response = arrival(List.of(arrivalOption(TransportMode.RAIL, 40, 5)));

        assertThat(ItineraryService.earliestStart(response)).isEqualTo(READY_AT);
    }

    @Test
    @DisplayName("latestLeaveAt이 있으면 택시 leaveByAt이 더 늦어도 latestLeaveAt이 마감이다")
    void latestLeaveAtWins() {
        OffsetDateTime leave = time("2026-11-09T14:53:00+09:00");

        assertThat(ItineraryService.latestEnd(departure(false, leave, time("2026-11-09T15:10:00+09:00"))))
                .isEqualTo(leave);
    }

    @Test
    @DisplayName("대중교통 편이 없으면 쓸 수 있는 택시·차량 호출 중 가장 이른 leaveByAt이 마감이다")
    void earliestUsableLeaveBy() {
        OffsetDateTime early = time("2026-11-09T03:10:00+09:00");

        assertThat(ItineraryService.latestEnd(departure(false, null, null, time("2026-11-09T04:10:00+09:00"), early)))
                .isEqualTo(early);
    }

    @Test
    @DisplayName("착륙 후 시간이 없거나 수단이 전혀 없으면 마감 값이 없다")
    void noLatestEnd() {
        assertThat(ItineraryService.latestEnd(departure(true, null, (OffsetDateTime) null))).isNull();
        assertThat(ItineraryService.latestEnd(departure(false, null))).isNull();
    }
}
