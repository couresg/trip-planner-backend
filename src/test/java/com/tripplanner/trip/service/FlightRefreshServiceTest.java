package com.tripplanner.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tripplanner.catalog.domain.Area;
import com.tripplanner.catalog.domain.City;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.domain.FlightPoint;
import com.tripplanner.flight.service.FlightService;
import com.tripplanner.flight.service.FlightService.FlightLookup;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.repository.TripFlightRepository;
import com.tripplanner.trip.service.FlightRefreshService.FlightRefresh;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 가는 편 KE723: 2026-11-05 ICN 12:00 출발(KST) → KIX 14:10 도착(JST), 오는 편 KE724: 2026-11-08 KIX 18:00 출발.
 * 생성(마지막 확인) 시각 2026-10-29. FlightService는 목으로 지연·결항·장애·캐시 시각을 흉내 낸다.
 */
class FlightRefreshServiceTest {

    private static final String TRIP_ID = "9f1c2b9e-6a43-4c1e-9f3a-2f6b1d7e8a01";
    private static final String TOKEN = "3a7e4d21-0c55-4b8f-a1d2-5e6f7a8b9c0d";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final LocalDate START = LocalDate.of(2026, 11, 5);
    private static final LocalDate END = LocalDate.of(2026, 11, 8);
    private static final Instant CREATED = Instant.parse("2026-10-29T00:00:00Z");
    private static final ZonedDateTime OUT_DEP = ZonedDateTime.of(2026, 11, 5, 12, 0, 0, 0, SEOUL);
    private static final ZonedDateTime OUT_ARR = ZonedDateTime.of(2026, 11, 5, 14, 10, 0, 0, TOKYO);
    private static final ZonedDateTime RET_DEP = ZonedDateTime.of(2026, 11, 8, 18, 0, 0, 0, TOKYO);
    private static final FlightNumber KE723 = FlightNumber.of("KE723");
    private static final FlightNumber KE724 = FlightNumber.of("KE724");
    private static final EnumSet<FlightDirection> BOTH = EnumSet.allOf(FlightDirection.class);
    private static final EnumSet<FlightDirection> OUT = EnumSet.of(FlightDirection.OUTBOUND);
    private static final EnumSet<FlightDirection> RET = EnumSet.of(FlightDirection.RETURN);

    private final TripService tripService = mock(TripService.class);
    private final TripFlightRepository tripFlightRepository = mock(TripFlightRepository.class);
    private final FlightService flightService = mock(FlightService.class);
    private TripFlight outbound;
    private TripFlight returnFlight;

    @BeforeEach
    void setUp() {
        Trip trip = new Trip("hash", mock(City.class), mock(Area.class), START, END, CREATED);
        outbound = withId(TripFlight.fromLeg(FlightDirection.OUTBOUND, "KE723", START, outboundLeg(null, "Expected"),
                CREATED), 1L);
        returnFlight = withId(TripFlight.fromLeg(FlightDirection.RETURN, "KE724", END, returnLeg(null, "Expected"),
                CREATED), 2L);
        trip.addFlight(outbound);
        trip.addFlight(returnFlight);
        given(tripService.findOwned(TRIP_ID, TOKEN)).willReturn(trip);
        given(tripFlightRepository.lockById(1L)).willReturn(Optional.of(outbound));
        given(tripFlightRepository.lockById(2L)).willReturn(Optional.of(returnFlight));
    }

    @Test
    @DisplayName("생성 뒤 지연이 생기면 가는 편 변경 도착·status를 저장하고 확인 시각은 요청 시각이 아니라 값을 받은 시각이다")
    void delayAppearsAfterCreation() {
        Instant now = OUT_DEP.minusHours(3).toInstant();
        Instant fetchedAt = now.minusSeconds(240);
        stub(KE723, START, fresh(fetchedAt, outboundLeg(OUT_ARR.plusMinutes(50), "Delayed")));

        FlightRefresh refresh = service(now).refresh(TRIP_ID, TOKEN, OUT);

        assertThat(refresh.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.REFRESHED);
        assertThat(refresh.of(FlightDirection.RETURN)).isNull();
        assertThat(outbound.arrivalAt()).isEqualTo(OUT_ARR.plusMinutes(50).toInstant());
        assertThat(outbound.getScheduledArrUtc()).isEqualTo(OUT_ARR.toInstant());
        assertThat(outbound.getStatus()).isEqualTo("Delayed");
        assertThat(outbound.getFetchedAt()).isEqualTo(fetchedAt);
    }

    @Test
    @DisplayName("저장된 지연이 줄거나 없어지면 새 값으로 덮어쓴다")
    void delayShrinks() {
        outbound.refresh(outboundLeg(OUT_ARR.plusMinutes(90), "Delayed"), CREATED.plusSeconds(60));
        Instant now = OUT_DEP.minusHours(1).toInstant();
        stub(KE723, START, fresh(now, outboundLeg(null, "Expected")));

        FlightRefresh refresh = service(now).refresh(TRIP_ID, TOKEN, OUT);

        assertThat(refresh.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.REFRESHED);
        assertThat(outbound.getRevisedArrUtc()).isNull();
        assertThat(outbound.arrivalAt()).isEqualTo(OUT_ARR.toInstant());
        assertThat(outbound.getStatus()).isEqualTo("Expected");
    }

    @Test
    @DisplayName("결항이면 status를 Canceled로 갱신하고, 가는 편은 도착 3시간이 지나 AFTER_WINDOW다")
    void cancellation() {
        Instant now = RET_DEP.minusHours(5).toInstant();
        stub(KE724, END, fresh(now, returnLeg(null, "Canceled")));

        FlightRefresh refresh = service(now).refresh(TRIP_ID, TOKEN, BOTH);

        assertThat(refresh.of(FlightDirection.RETURN)).isEqualTo(FlightCheckResult.REFRESHED);
        assertThat(refresh.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.AFTER_WINDOW);
        assertThat(returnFlight.getStatus()).isEqualTo("Canceled");
        assertThat(returnFlight.departureAt()).isEqualTo(RET_DEP.toInstant());
    }

    @Test
    @DisplayName("예정 출발 48시간 1초 전이면 조회하지 않고 BEFORE_WINDOW, 정확히 48시간 전이면 조회한다")
    void windowStartsFortyEightHoursBeforeDeparture() {
        Instant fortyEight = OUT_DEP.minusHours(48).toInstant();
        stub(KE723, START, fresh(fortyEight, outboundLeg(null, "Expected")));

        FlightRefresh early = service(fortyEight.minusSeconds(1)).refresh(TRIP_ID, TOKEN, BOTH);

        assertThat(early.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.BEFORE_WINDOW);
        assertThat(early.of(FlightDirection.RETURN)).isEqualTo(FlightCheckResult.BEFORE_WINDOW);
        verifyNoInteractions(flightService, tripFlightRepository);

        FlightRefresh onTime = service(fortyEight).refresh(TRIP_ID, TOKEN, BOTH);

        assertThat(onTime.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.REFRESHED);
        verify(flightService, never()).lookupWithMeta(KE724, END);
    }

    @Test
    @DisplayName("가는 편은 변경 도착 3시간 뒤까지, 오는 편은 변경 출발까지만 조회하고 그 뒤는 AFTER_WINDOW다")
    void windowEnds() {
        outbound.refresh(outboundLeg(OUT_ARR.plusHours(1), "Delayed"), CREATED.plusSeconds(60));
        returnFlight.refresh(returnLeg(RET_DEP.plusHours(1), "Delayed"), CREATED.plusSeconds(60));
        Instant outboundLimit = OUT_ARR.plusHours(4).toInstant();
        Instant returnLimit = RET_DEP.plusHours(1).toInstant();
        stub(KE723, START, fresh(outboundLimit, outboundLeg(OUT_ARR.plusHours(1), "Arrived")));
        stub(KE724, END, fresh(returnLimit, returnLeg(RET_DEP.plusHours(1), "Departed")));

        assertThat(service(outboundLimit).refresh(TRIP_ID, TOKEN, OUT).of(FlightDirection.OUTBOUND))
                .isEqualTo(FlightCheckResult.REFRESHED);
        assertThat(service(outboundLimit.plusSeconds(1)).refresh(TRIP_ID, TOKEN, OUT).of(FlightDirection.OUTBOUND))
                .isEqualTo(FlightCheckResult.AFTER_WINDOW);
        assertThat(service(returnLimit).refresh(TRIP_ID, TOKEN, RET).of(FlightDirection.RETURN))
                .isEqualTo(FlightCheckResult.REFRESHED);
        assertThat(service(returnLimit.plusSeconds(1)).refresh(TRIP_ID, TOKEN, RET).of(FlightDirection.RETURN))
                .isEqualTo(FlightCheckResult.AFTER_WINDOW);
    }

    @Test
    @DisplayName("수동 입력 편은 구간 안이어도 조회하지 않는다")
    void manualFlightNotLookedUp() {
        Trip manualTrip = new Trip("hash", mock(City.class), mock(Area.class), START, END, CREATED);
        manualTrip.addFlight(withId(TripFlight.manualOutbound("KE723", START, "KIX", OUT_ARR.toInstant(), CREATED),
                1L));
        given(tripService.findOwned(TRIP_ID, TOKEN)).willReturn(manualTrip);

        FlightRefresh refresh = service(OUT_ARR.minusHours(1).toInstant()).refresh(TRIP_ID, TOKEN, OUT);

        assertThat(refresh.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.MANUAL);
        verifyNoInteractions(flightService, tripFlightRepository);
    }

    @Test
    @DisplayName("공급자 장애·찾지 못함·공항이 다른 구간뿐·만료된 캐시 대체면 저장된 값을 그대로 두고 FAILED다")
    void failuresKeepStoredValues() {
        Instant now = OUT_DEP.minusHours(2).toInstant();
        given(flightService.lookupWithMeta(KE723, START)).willThrow(new FlightProviderException("timeout", null));
        assertFailedAndUnchanged(now);

        willThrow(new BusinessException(ErrorCode.FLIGHT_NOT_FOUND)).given(flightService).lookupWithMeta(KE723, START);
        assertFailedAndUnchanged(now);

        FlightLeg diverted = new FlightLeg(new FlightPoint("ICN", SEOUL, OUT_DEP.toInstant(), null, "2"),
                new FlightPoint("NGO", TOKYO, OUT_ARR.toInstant(), null, null), "Diverted");
        willReturn(fresh(now, diverted)).given(flightService).lookupWithMeta(KE723, START);
        assertFailedAndUnchanged(now);

        // 공급자 장애 중 만료된 DB 캐시: 값이 저장된 것보다 나중이어도 지금 확인한 정보가 아니다.
        willReturn(new FlightLookup(List.of(outboundLeg(OUT_ARR.plusMinutes(30), "Delayed")), now.minusSeconds(3600),
                true)).given(flightService).lookupWithMeta(KE723, START);
        assertFailedAndUnchanged(now);
    }

    @Test
    @DisplayName("저장된 값과 같은 시각에 받은 캐시 값이면 잠금·UPDATE 없이 REFRESHED다")
    void sameCacheValueSkipsWrite() {
        Instant now = OUT_DEP.minusHours(2).toInstant();
        stub(KE723, START, fresh(CREATED, outboundLeg(OUT_ARR.plusMinutes(50), "Delayed")));

        FlightRefresh refresh = service(now).refresh(TRIP_ID, TOKEN, OUT);

        assertThat(refresh.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.REFRESHED);
        verify(tripFlightRepository, never()).lockById(any());
        assertThat(outbound.getFetchedAt()).isEqualTo(CREATED);
    }

    @Test
    @DisplayName("캐시가 갈라져 더 먼저 받은 값이 오면(다른 인스턴스·저장 실패) 더 나중에 받은 저장 값을 덮어쓰지 않는다")
    void olderCacheValueDoesNotOverwrite() {
        Instant newer = OUT_DEP.minusHours(1).toInstant();
        outbound.refresh(outboundLeg(OUT_ARR.plusMinutes(30), "Delayed"), newer);
        stub(KE723, START, fresh(newer.minusSeconds(300), outboundLeg(OUT_ARR.plusMinutes(10), "Delayed")));

        service(newer.plusSeconds(60)).refresh(TRIP_ID, TOKEN, OUT);

        verify(tripFlightRepository, never()).lockById(any());
        assertThat(outbound.arrivalAt()).isEqualTo(OUT_ARR.plusMinutes(30).toInstant());
        assertThat(outbound.getFetchedAt()).isEqualTo(newer);
    }

    @Test
    @DisplayName("잠근 뒤 다시 비교해 그 사이 다른 요청이 저장한 더 나중 값이나 같은 값은 덮어쓰지 않는다")
    void entityGuardIsStrict() {
        Instant newer = OUT_DEP.minusHours(1).toInstant();
        outbound.refresh(outboundLeg(OUT_ARR.plusMinutes(30), "Delayed"), newer);

        assertThat(outbound.refresh(outboundLeg(OUT_ARR.plusMinutes(10), "Delayed"), newer.minusSeconds(5))).isFalse();
        assertThat(outbound.refresh(outboundLeg(OUT_ARR.plusMinutes(10), "Delayed"), newer)).isFalse();
        assertThat(outbound.arrivalAt()).isEqualTo(OUT_ARR.plusMinutes(30).toInstant());
        assertThat(outbound.refresh(outboundLeg(OUT_ARR.plusMinutes(10), "Delayed"), newer.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("없는 여행·토큰 불일치는 외부 조회 전에 그대로 던진다")
    void ownershipCheckedFirst() {
        given(tripService.findOwned(TRIP_ID, "wrong"))
                .willThrow(new BusinessException(ErrorCode.FORBIDDEN_TRIP, "이 여행에 접근할 권한이 없습니다."));

        assertThatThrownBy(() -> service(OUT_DEP.toInstant()).refresh(TRIP_ID, "wrong", BOTH))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(flightService);
    }

    private void assertFailedAndUnchanged(Instant now) {
        FlightRefresh refresh = service(now).refresh(TRIP_ID, TOKEN, OUT);

        assertThat(refresh.of(FlightDirection.OUTBOUND)).isEqualTo(FlightCheckResult.FAILED);
        assertThat(outbound.arrivalAt()).isEqualTo(OUT_ARR.toInstant());
        assertThat(outbound.getStatus()).isEqualTo("Expected");
        assertThat(outbound.getFetchedAt()).isEqualTo(CREATED);
        verify(tripFlightRepository, never()).lockById(any());
    }

    private void stub(FlightNumber flightNumber, LocalDate date, FlightLookup lookup) {
        willReturn(lookup).given(flightService).lookupWithMeta(flightNumber, date);
    }

    private static FlightLookup fresh(Instant fetchedAt, FlightLeg leg) {
        return new FlightLookup(List.of(leg), fetchedAt, false);
    }

    private FlightRefreshService service(Instant now) {
        return new FlightRefreshService(tripService, tripFlightRepository, flightService,
                Clock.fixed(now, ZoneOffset.UTC), mock(PlatformTransactionManager.class));
    }

    private static FlightLeg outboundLeg(ZonedDateTime revisedArrival, String status) {
        return new FlightLeg(new FlightPoint("ICN", SEOUL, OUT_DEP.toInstant(), null, "2"),
                new FlightPoint("KIX", TOKYO, OUT_ARR.toInstant(),
                        revisedArrival == null ? null : revisedArrival.toInstant(), "1"),
                status);
    }

    private static FlightLeg returnLeg(ZonedDateTime revisedDeparture, String status) {
        return new FlightLeg(new FlightPoint("KIX", TOKYO, RET_DEP.toInstant(),
                revisedDeparture == null ? null : revisedDeparture.toInstant(), "1"),
                new FlightPoint("ICN", SEOUL, RET_DEP.plusHours(2).toInstant(), null, "2"), status);
    }

    private static TripFlight withId(TripFlight flight, long id) {
        ReflectionTestUtils.setField(flight, "id", id);
        return flight;
    }
}
