package com.tripplanner.itinerary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripplanner.itinerary.domain.ItineraryWarning;
import com.tripplanner.itinerary.service.ItineraryWarningCalculator.Visit;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** 2026-11-05는 목요일, 11-06 금요일, 11-07 토요일, 11-08 일요일, 11-09 월요일(Asia/Tokyo). */
class ItineraryWarningCalculatorTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate THU = LocalDate.of(2026, 11, 5);
    private static final LocalDate FRI = LocalDate.of(2026, 11, 6);
    private static final LocalDate SAT = LocalDate.of(2026, 11, 7);
    private static final LocalDate SUN = LocalDate.of(2026, 11, 8);
    private static final LocalDate MON = LocalDate.of(2026, 11, 9);
    /** 어떤 방문도 시간 경고가 나지 않는 값 */
    private static final Instant LONG_BEFORE = at("2026-11-01T00:00:00+09:00");
    private static final Instant LONG_AFTER = at("2026-11-30T00:00:00+09:00");
    private static final String THU_9_TO_17 = """
            [{"dayOfWeek":"THU","open":"09:00","close":"17:00"}]""";
    private static final String FRI_18_TO_02 = """
            [{"dayOfWeek":"FRI","open":"18:00","close":"02:00"}]""";

    private final ItineraryWarningCalculator calculator = new ItineraryWarningCalculator(JsonMapper.builder().build(),
            ItineraryProperties.defaults());

    private static Instant at(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    private static LocalTime time(String hhmm) {
        return LocalTime.parse(hhmm);
    }

    /** 체류 0분: 방문 시작이 영업 구간 안인지만 보는 경계 테스트용 */
    private static Visit visit(LocalDate date, String hhmm, String hoursJson) {
        return new Visit(501L, date, hhmm == null ? null : time(hhmm), 0, false, hoursJson);
    }

    private List<ItineraryWarning> hoursOnly(LocalDate date, String hhmm, String hoursJson) {
        return calculator.calculate(visit(date, hhmm, hoursJson), LONG_BEFORE, LONG_AFTER, TOKYO);
    }

    private List<ItineraryWarning> hoursWithDuration(LocalDate date, String hhmm, int durationMin, String hoursJson) {
        return calculator.calculate(new Visit(501L, date, time(hhmm), durationMin, false, hoursJson), LONG_BEFORE,
                LONG_AFTER, TOKYO);
    }

    private List<ItineraryWarning> times(LocalDate date, String hhmm, int durationMin, Instant earliest,
            Instant latest) {
        return calculator.calculate(new Visit(501L, date, hhmm == null ? null : time(hhmm), durationMin, true, "[]"),
                earliest, latest, TOKYO);
    }

    @Test
    @DisplayName("방문 시각이 여는 시각과 같으면 영업 중이다")
    void openBoundaryIsOpen() {
        assertThat(hoursOnly(THU, "09:00", THU_9_TO_17)).isEmpty();
    }

    @Test
    @DisplayName("여는 시각 1분 전이면 영업시간 밖이다")
    void oneMinuteBeforeOpen() {
        assertThat(hoursOnly(THU, "08:59", THU_9_TO_17)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("닫는 시각 1분 전은 영업 중이고 닫는 시각과 같으면 영업시간 밖이다")
    void closeBoundaryIsClosed() {
        assertThat(hoursOnly(THU, "16:59", THU_9_TO_17)).isEmpty();
        assertThat(hoursOnly(THU, "17:00", THU_9_TO_17)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("해당 요일 항목이 없으면 휴무라 영업시간 밖이다")
    void closedDay() {
        assertThat(hoursOnly(FRI, "10:00", THU_9_TO_17)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("영업시간 목록이 비어 있고 상시 개방이 아니면 정보 없음으로 보고 경고하지 않는다")
    void unknownHours() {
        assertThat(hoursOnly(THU, "03:00", "[]")).isEmpty();
    }

    @Test
    @DisplayName("상시 개방 장소는 영업시간 목록이 있어도 경고하지 않는다")
    void alwaysOpen() {
        Visit visit = new Visit(506L, FRI, time("03:00"), 45, true, THU_9_TO_17);

        assertThat(calculator.calculate(visit, LONG_BEFORE, LONG_AFTER, TOKYO)).isEmpty();
    }

    @Test
    @DisplayName("방문 시각이 없으면 휴무일이어도 영업시간 경고를 하지 않는다")
    void nullPlannedTimeSkipsHours() {
        assertThat(hoursOnly(FRI, null, THU_9_TO_17)).isEmpty();
    }

    @Test
    @DisplayName("같은 요일에 영업 구간이 둘이면 그 사이 시각만 영업시간 밖이다")
    void splitHours() {
        String hours = """
                [{"dayOfWeek":"THU","open":"11:00","close":"14:00"},
                 {"dayOfWeek":"THU","open":"17:00","close":"22:00"}]""";

        assertThat(hoursOnly(THU, "13:59", hours)).isEmpty();
        assertThat(hoursOnly(THU, "15:00", hours)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
        assertThat(hoursOnly(THU, "17:00", hours)).isEmpty();
    }

    @Test
    @DisplayName("금 18:00~02:00이면 금요일 18시부터 자정 전까지 영업 중이고 18시 전은 영업시간 밖이다")
    void overnightBeforeMidnight() {
        assertThat(hoursOnly(FRI, "17:59", FRI_18_TO_02)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
        assertThat(hoursOnly(FRI, "18:00", FRI_18_TO_02)).isEmpty();
        assertThat(hoursOnly(FRI, "23:59", FRI_18_TO_02)).isEmpty();
    }

    @Test
    @DisplayName("금 18:00~02:00이면 토요일 00:00~02:00 전까지는 금요일 항목으로 영업 중이고 02:00부터 영업시간 밖이다")
    void overnightAfterMidnight() {
        assertThat(hoursOnly(SAT, "00:00", FRI_18_TO_02)).isEmpty();
        assertThat(hoursOnly(SAT, "01:59", FRI_18_TO_02)).isEmpty();
        assertThat(hoursOnly(SAT, "02:00", FRI_18_TO_02)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
        assertThat(hoursOnly(SAT, "18:00", FRI_18_TO_02)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("금 18:00~02:00이면 금요일 새벽 01시는 전날(목) 항목이 없어 영업시간 밖이다")
    void overnightDoesNotCoverOwnEarlyMorning() {
        assertThat(hoursOnly(FRI, "01:00", FRI_18_TO_02)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("일 20:00~01:00의 자정 넘김은 주 경계를 넘어 월요일 00:30까지 덮는다")
    void overnightAcrossWeekBoundary() {
        String hours = """
                [{"dayOfWeek":"SUN","open":"20:00","close":"01:00"}]""";

        assertThat(hoursOnly(SUN, "20:00", hours)).isEmpty();
        assertThat(hoursOnly(MON, "00:30", hours)).isEmpty();
        assertThat(hoursOnly(MON, "01:00", hours)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("close가 00:00이면 자정까지 영업이고 다음 날 00:00은 영업시간 밖이다")
    void closeAtMidnight() {
        String hours = """
                [{"dayOfWeek":"FRI","open":"18:00","close":"00:00"}]""";

        assertThat(hoursOnly(FRI, "23:59", hours)).isEmpty();
        assertThat(hoursOnly(SAT, "00:00", hours)).containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("시작 + 체류가 닫는 시각과 같으면 경고가 없고 1분 넘으면 영업시간 밖이다")
    void durationBoundaryAtClose() {
        assertThat(hoursWithDuration(THU, "15:30", 90, THU_9_TO_17)).isEmpty();
        assertThat(hoursWithDuration(THU, "15:31", 90, THU_9_TO_17))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("시작이 영업시간 밖이면 체류와 상관없이 한 번만 경고한다")
    void startOutsideStillWarns() {
        assertThat(hoursWithDuration(THU, "08:00", 30, THU_9_TO_17))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("금 18:00~02:00에 금요일 저녁 시작이면 체류가 자정을 넘겨도 토요일 02:00까지는 경고가 없다")
    void durationInOvernightIntervalFromEvening() {
        assertThat(hoursWithDuration(FRI, "23:00", 180, FRI_18_TO_02)).isEmpty();
        assertThat(hoursWithDuration(FRI, "23:00", 181, FRI_18_TO_02))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("금 18:00~02:00에 토요일 새벽 시작이면 같은 구간의 02:00과 비교한다")
    void durationInOvernightIntervalAfterMidnight() {
        assertThat(hoursWithDuration(SAT, "01:00", 60, FRI_18_TO_02)).isEmpty();
        assertThat(hoursWithDuration(SAT, "01:00", 61, FRI_18_TO_02))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("close가 00:00이면 체류가 정확히 자정에 끝날 때까지 경고가 없다")
    void durationEndsAtMidnightClose() {
        String hours = """
                [{"dayOfWeek":"FRI","open":"18:00","close":"00:00"}]""";

        assertThat(hoursWithDuration(FRI, "23:00", 60, hours)).isEmpty();
        assertThat(hoursWithDuration(FRI, "23:00", 61, hours))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("영업 구간이 둘이면 시작을 포함한 구간의 닫는 시각만 본다 (다음 구간으로 이어지지 않는다)")
    void durationUsesIntervalContainingStart() {
        String hours = """
                [{"dayOfWeek":"THU","open":"11:00","close":"14:00"},
                 {"dayOfWeek":"THU","open":"17:00","close":"22:00"}]""";

        assertThat(hoursWithDuration(THU, "13:00", 60, hours)).isEmpty();
        assertThat(hoursWithDuration(THU, "13:00", 300, hours))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("겹치는 영업 구간은 가장 늦게 닫는 구간으로 판정한다")
    void overlappingIntervalsUseLatestClose() {
        String hours = """
                [{"dayOfWeek":"THU","open":"09:00","close":"12:00"},
                 {"dayOfWeek":"THU","open":"10:00","close":"17:00"}]""";

        assertThat(hoursWithDuration(THU, "11:00", 360, hours)).isEmpty();
        assertThat(hoursWithDuration(THU, "11:00", 361, hours))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS);
    }

    @Test
    @DisplayName("상시 개방과 영업시간 정보 없음은 체류가 길어도 경고하지 않는다")
    void durationIgnoredForAlwaysOpenAndUnknown() {
        assertThat(calculator.calculate(new Visit(506L, THU, time("16:00"), 600, true, THU_9_TO_17), LONG_BEFORE,
                LONG_AFTER, TOKYO)).isEmpty();
        assertThat(hoursWithDuration(THU, "16:00", 600, "[]")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{깨진 JSON",
            "",
            "null",
            "{\"dayOfWeek\":\"THU\"}",
            "[null]",
            "[{\"dayOfWeek\":\"XXX\",\"open\":\"09:00\",\"close\":\"17:00\"}]",
            "[{\"dayOfWeek\":\"THU\",\"open\":\"25:00\",\"close\":\"17:00\"}]",
            "[{\"dayOfWeek\":\"THU\",\"open\":\"09:00\"}]"})
    @DisplayName("영업시간을 해석할 수 없으면 예외 없이 영업시간 경고만 건너뛴다")
    void unparseableHours(String json) {
        assertThat(hoursOnly(THU, "03:00", json)).isEmpty();
    }

    @Test
    @DisplayName("영업시간을 해석할 수 없어도 도착·출발 경고는 그대로 계산한다")
    void unparseableHoursKeepsTimeWarnings() {
        assertThat(calculator.calculate(visit(THU, "03:00", "{깨진 JSON"), at("2026-11-05T15:48:00+09:00"), LONG_AFTER,
                TOKYO)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
    }

    @Test
    @DisplayName("방문 시각이 숙소 도착 예상과 같으면 경고가 없고 1분 이르면 BEFORE_ARRIVAL이다")
    void beforeArrivalBoundary() {
        Instant earliest = at("2026-11-05T15:48:00+09:00");

        assertThat(times(THU, "15:48", 60, earliest, LONG_AFTER)).isEmpty();
        assertThat(times(THU, "15:47", 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
    }

    @Test
    @DisplayName("방문 시각이 없으면 도착일 당일은 경고가 없고 그 전날만 BEFORE_ARRIVAL이다")
    void beforeArrivalWithoutTime() {
        Instant earliest = at("2026-11-06T15:48:00+09:00");

        assertThat(times(THU, null, 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(times(FRI, null, 60, earliest, LONG_AFTER)).isEmpty();
    }

    @Test
    @DisplayName("시작일 다음 날 새벽에 착륙하면 시작일 항목은 시각이 있든 없든 모두 BEFORE_ARRIVAL이다")
    void overnightArrivalStartDatePrecedesArrival() {
        Instant earliest = at("2026-11-06T02:30:00+09:00");

        assertThat(times(THU, "23:00", 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(times(THU, null, 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(times(FRI, "09:00", 60, earliest, LONG_AFTER)).isEmpty();
    }

    @Test
    @DisplayName("23시대 착륙으로 숙소 도착이 자정을 넘기면 다음 날 00:30 전 방문만 BEFORE_ARRIVAL이다")
    void lodgingArrivalPastMidnight() {
        Instant earliest = at("2026-11-06T00:30:00+09:00");

        assertThat(times(THU, "23:50", 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(times(THU, null, 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(times(FRI, "00:10", 60, earliest, LONG_AFTER)).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(times(FRI, "00:30", 60, earliest, LONG_AFTER)).isEmpty();
        assertThat(times(FRI, null, 60, earliest, LONG_AFTER)).isEmpty();
    }

    @Test
    @DisplayName("방문 + 체류 + 복귀 여유 30분이 마감과 같으면 경고가 없고 1분 넘으면 AFTER_DEPARTURE_LEAVE_TIME이다")
    void afterDepartureBoundary() {
        Instant latest = at("2026-11-09T14:53:00+09:00");

        assertThat(times(MON, "12:53", 90, LONG_BEFORE, latest)).isEmpty();
        assertThat(times(MON, "12:54", 90, LONG_BEFORE, latest))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("귀국일 방문 시작은 마감 전이어도 체류 시간이 마감을 넘기면 경고한다")
    void lastDayVisitDurationCrossesLatest() {
        Instant latest = at("2026-11-09T14:53:00+09:00");

        assertThat(times(MON, "14:00", 60, LONG_BEFORE, latest))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("방문 + 체류는 마감 전이어도 복귀 여유를 더해 마감을 넘기면 경고한다")
    void returnMarginAloneCrossesLatest() {
        Instant latest = at("2026-11-09T14:53:00+09:00");

        assertThat(times(MON, "13:40", 45, LONG_BEFORE, latest))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("복귀 여유는 설정값을 따른다")
    void returnMarginFromProperties() {
        Instant latest = at("2026-11-09T14:53:00+09:00");
        Visit visit = new Visit(501L, MON, time("13:23"), 90, true, "[]");

        assertThat(new ItineraryWarningCalculator(JsonMapper.builder().build(), new ItineraryProperties(0, null))
                .calculate(visit, LONG_BEFORE, latest, TOKYO)).isEmpty();
        assertThat(new ItineraryWarningCalculator(JsonMapper.builder().build(), new ItineraryProperties(1, null))
                .calculate(visit, LONG_BEFORE, latest, TOKYO))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("시각 없는 항목은 복귀 여유와 무관하게 날짜로만 비교한다")
    void returnMarginIgnoredWithoutTime() {
        Instant latest = at("2026-11-09T00:10:00+09:00");

        assertThat(times(MON, null, 60, LONG_BEFORE, latest)).isEmpty();
    }

    @Test
    @DisplayName("마감이 전날 밤이면 전날 항목의 체류가 자정을 넘어 비교되고 시각 없는 귀국일 항목은 경고한다")
    void latestOnPreviousNight() {
        Instant latest = at("2026-11-08T23:30:00+09:00");

        assertThat(times(SUN, "23:00", 60, LONG_BEFORE, latest))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
        assertThat(times(SUN, null, 60, LONG_BEFORE, latest)).isEmpty();
        assertThat(times(MON, null, 60, LONG_BEFORE, latest))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("체류가 자정을 넘겨도 마감이 다음 날 새벽이면 경고하지 않는다")
    void durationCrossesMidnightBeforeLatest() {
        Instant latest = at("2026-11-09T00:30:00+09:00");

        assertThat(times(SUN, "23:00", 60, LONG_BEFORE, latest)).isEmpty();
        assertThat(times(SUN, "23:01", 60, LONG_BEFORE, latest))
                .containsExactly(ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("마감 값이 없으면 출발 경고를 하지 않는다")
    void noLatestValue() {
        assertThat(times(MON, "23:00", 480, LONG_BEFORE, null)).isEmpty();
        assertThat(times(MON, null, 480, LONG_BEFORE, null)).isEmpty();
    }

    @Test
    @DisplayName("경고가 여럿이면 영업시간, 도착 전, 출발 마감 후 순서다")
    void warningOrder() {
        Instant earliest = at("2026-11-05T12:00:00+09:00");
        Instant latest = at("2026-11-05T08:00:00+09:00");

        assertThat(calculator.calculate(visit(THU, "08:30", THU_9_TO_17), earliest, latest, TOKYO))
                .containsExactly(ItineraryWarning.OUTSIDE_OPENING_HOURS, ItineraryWarning.BEFORE_ARRIVAL,
                        ItineraryWarning.AFTER_DEPARTURE_LEAVE_TIME);
    }

    @Test
    @DisplayName("방문 시각은 도시 timezone으로 해석한다 (같은 벽시계라도 서울과 뉴욕은 다른 순간)")
    void usesCityTimezone() {
        Instant earliest = at("2026-11-05T12:00:00+09:00");

        assertThat(calculator.calculate(new Visit(501L, THU, time("11:00"), 60, true, "[]"), earliest, LONG_AFTER,
                ZoneId.of("Asia/Seoul"))).containsExactly(ItineraryWarning.BEFORE_ARRIVAL);
        assertThat(calculator.calculate(new Visit(501L, THU, time("11:00"), 60, true, "[]"), earliest, LONG_AFTER,
                ZoneId.of("America/New_York"))).isEmpty();
    }
}
