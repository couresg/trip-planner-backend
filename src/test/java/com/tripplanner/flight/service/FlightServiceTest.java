package com.tripplanner.flight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.client.FlightClient;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.flight.domain.FlightCacheEntry;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.domain.FlightPoint;
import com.tripplanner.flight.repository.FlightCacheRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class FlightServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final FlightNumber KE723 = FlightNumber.of("KE723");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 5);
    /** KE723 출발 시각 2026-11-05T03:00Z 의 정확히 48시간 전 */
    private static final Instant FORTY_EIGHT_HOURS_BEFORE = Instant.parse("2026-11-03T03:00:00Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final FlightCacheRepository repository = mock(FlightCacheRepository.class);
    private final FakeFlightClient client = new FakeFlightClient();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-29T00:00:00Z"));
    private FlightService service;

    @BeforeEach
    void setUp() {
        given(repository.findByFlightNoAndFlightDate(any(), any())).willReturn(Optional.empty());
        client.legs = List.of(ke723());
        service = new FlightService(client, repository, objectMapper, clock);
    }

    @Test
    @DisplayName("캐시가 없으면 공급자를 호출하고 메모리와 DB에 저장한다")
    void missCallsProviderAndStores() {
        List<FlightLeg> legs = service.lookup(KE723, DATE);

        assertThat(legs).containsExactly(ke723());
        assertThat(client.calls.get()).isEqualTo(1);
        FlightCacheEntry saved = savedEntry();
        assertThat(saved.getFlightNo()).isEqualTo("KE723");
        assertThat(saved.getFlightDate()).isEqualTo(DATE);
        assertThat(saved.getFetchedAt()).isEqualTo(clock.instant());
        assertThat(objectMapper.readValue(saved.getResponseJson(), FlightLeg[].class)).containsExactly(ke723());
    }

    @Test
    @DisplayName("메모리 캐시가 유효하면 DB와 공급자를 다시 조회하지 않는다")
    void memoryHit() {
        service.lookup(KE723, DATE);
        clock.advance(Duration.ofHours(5));

        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        assertThat(client.calls.get()).isEqualTo(1);
        verify(repository, times(1)).findByFlightNoAndFlightDate("KE723", DATE);
    }

    @Test
    @DisplayName("메모리 캐시가 만료되면 다시 공급자를 호출한다")
    void memoryExpired() {
        service.lookup(KE723, DATE);
        clock.advance(FlightService.LONG_TTL);

        service.lookup(KE723, DATE);

        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("메모리에 없고 DB 캐시가 만료 전이면 DB 값을 쓰고 공급자를 호출하지 않는다")
    void dbHit() {
        given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(entry(
                clock.instant().plusSeconds(60))));

        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        assertThat(client.calls.get()).isZero();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("DB 캐시가 만료되었으면 공급자를 호출하고 같은 행을 갱신한다")
    void dbExpiredRefreshesRow() {
        FlightCacheEntry expired = entry(clock.instant());
        given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(expired));

        service.lookup(KE723, DATE);

        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(savedEntry()).isSameAs(expired);
        assertThat(expired.getExpiresAt()).isEqualTo(clock.instant().plus(FlightService.LONG_TTL));
    }

    @Test
    @DisplayName("출발이 정확히 48시간 후면 TTL은 10분이다")
    void ttlAtFortyEightHours() {
        clock.set(FORTY_EIGHT_HOURS_BEFORE);

        service.lookup(KE723, DATE);

        assertThat(savedEntry().getExpiresAt()).isEqualTo(FORTY_EIGHT_HOURS_BEFORE.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("출발이 48시간 1분 후면 TTL은 6시간이다")
    void ttlJustOverFortyEightHours() {
        Instant now = FORTY_EIGHT_HOURS_BEFORE.minus(Duration.ofMinutes(1));
        clock.set(now);

        service.lookup(KE723, DATE);

        assertThat(savedEntry().getExpiresAt()).isEqualTo(now.plus(Duration.ofHours(6)));
    }

    @Test
    @DisplayName("출발 48시간 이내 편은 10분 뒤 메모리 캐시가 만료되어 공급자를 다시 호출한다")
    void shortTtlExpiresAfterTenMinutes() {
        clock.set(FORTY_EIGHT_HOURS_BEFORE);
        service.lookup(KE723, DATE);

        clock.advance(Duration.ofMinutes(9));
        service.lookup(KE723, DATE);
        assertThat(client.calls.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        service.lookup(KE723, DATE);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("공급자 장애 시 만료된 DB 캐시가 있으면 그 값을 반환한다")
    void staleFallback() {
        given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(entry(
                clock.instant().minus(Duration.ofDays(1)))));
        client.failure = new FlightProviderException("HTTP 429", null);

        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("공급자 장애 시 캐시가 전혀 없으면 FLIGHT_PROVIDER_UNAVAILABLE을 던진다")
    void providerUnavailable() {
        client.failure = new FlightProviderException("HTTP 503", null);

        assertThatThrownBy(() -> service.lookup(KE723, DATE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE);
    }

    @Test
    @DisplayName("찾지 못한 편명은 DB에 저장하지 않고 10분 동안 메모리에서 FLIGHT_NOT_FOUND로 응답한다")
    void notFoundNegativeCache() {
        client.legs = List.of();

        assertNotFound();
        clock.advance(Duration.ofMinutes(9));
        assertNotFound();
        assertThat(client.calls.get()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(1));
        assertNotFound();
        assertThat(client.calls.get()).isEqualTo(2);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("구간은 출발 시각 순으로 정렬된다")
    void legsSortedByDeparture() {
        FlightLeg second = leg("FUK", "2026-11-05T01:30:00Z", "KIX", "2026-11-05T02:40:00Z");
        FlightLeg first = leg("ICN", "2026-11-04T23:00:00Z", "FUK", "2026-11-05T00:20:00Z");
        client.legs = List.of(second, first);

        assertThat(service.lookup(FlightNumber.of("TW9901"), DATE)).containsExactly(first, second);
    }

    @Test
    @DisplayName("가는 편은 도착 공항이 도시 공항인 구간, 오는 편은 출발 공항이 도시 공항인 구간을 고른다")
    void selectLegByAirport() {
        FlightLeg icnFuk = leg("ICN", "2026-11-04T23:00:00Z", "FUK", "2026-11-05T00:20:00Z");
        FlightLeg fukKix = leg("FUK", "2026-11-05T01:30:00Z", "KIX", "2026-11-05T02:40:00Z");
        List<FlightLeg> legs = List.of(icnFuk, fukKix);

        assertThat(service.findArrivingAt(legs, Set.of("KIX", "ITM"))).contains(fukKix);
        assertThat(service.findArrivingAt(legs, Set.of("FUK"))).contains(icnFuk);
        assertThat(service.findDepartingFrom(legs, Set.of("FUK"))).contains(fukKix);
        assertThat(service.findDepartingFrom(legs, Set.of("KIX"))).isEmpty();
        assertThat(service.findArrivingAt(legs, Set.of("NRT", "HND"))).isEmpty();
    }

    @Test
    @DisplayName("같은 편명·날짜의 동시 요청은 공급자를 한 번만 호출하고 모두 같은 결과를 받는다")
    void concurrentMissesCallProviderOnce() throws InterruptedException {
        client.entered = new CountDownLatch(1);
        client.release = new CountDownLatch(1);
        List<List<FlightLeg>> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> results.add(service.lookup(KE723, DATE)));
            threads.add(thread);
            thread.start();
        }

        assertThat(client.entered.await(5, TimeUnit.SECONDS)).isTrue();
        // 공급자 호출 중인 스레드와 결과를 기다리는 나머지 스레드가 모두 대기 상태가 된 뒤 응답을 풀어 준다.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!threads.stream().allMatch(FlightServiceTest::isBlocked) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        client.release.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }

        assertThat(results).hasSize(8).allSatisfy(legs -> assertThat(legs).containsExactly(ke723()));
        assertThat(client.calls.get()).isEqualTo(1);
        verify(repository, times(1)).save(any());
    }

    @Test
    @DisplayName("공급자 장애 후 30초 동안은 공급자를 호출하지 않고 503을 반환한다 (다른 편명 포함)")
    void providerFailureBackoff() {
        client.failure = new FlightProviderException("HTTP 503", null);

        assertUnavailable(KE723);
        clock.advance(Duration.ofSeconds(29));
        assertUnavailable(KE723);
        assertUnavailable(FlightNumber.of("OZ112"));
        assertThat(client.calls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        assertUnavailable(KE723);
        assertThat(client.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("장애 대기 중에도 만료된 DB 캐시가 있으면 공급자 호출 없이 그 값을 반환한다")
    void backoffUsesStaleRow() {
        client.failure = new FlightProviderException("HTTP 503", null);
        assertUnavailable(KE723);
        given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(entry(
                clock.instant().minus(Duration.ofHours(1)))));

        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB 저장이 실패해도 조회 결과를 반환하고 메모리 캐시는 유지된다")
    void saveFailureIsIgnored() {
        given(repository.save(any())).willThrow(new DataAccessResourceFailureException("db down"));

        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("만료 전 DB 행의 JSON이 손상되었으면 캐시가 없는 것으로 보고 공급자 결과로 같은 행을 갱신한다")
    void corruptFreshRowIsMiss() {
        FlightCacheEntry corrupt = new FlightCacheEntry("KE723", DATE, "{broken", clock.instant(),
                clock.instant().plus(Duration.ofHours(1)));
        given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(corrupt));

        assertThat(service.lookup(KE723, DATE)).containsExactly(ke723());
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(savedEntry()).isSameAs(corrupt);
        assertThat(objectMapper.readValue(corrupt.getResponseJson(), FlightLeg[].class)).containsExactly(ke723());
    }

    @Test
    @DisplayName("공급자 장애 시 만료된 DB 행의 JSON이 손상되었으면 503을 반환한다")
    void corruptStaleRowIsUnavailable() {
        for (String json : List.of("{broken", "null", "[]", "[null]")) {
            given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(new FlightCacheEntry(
                    "KE723", DATE, json, clock.instant().minus(Duration.ofDays(1)), clock.instant())));
            client.failure = new FlightProviderException("HTTP 503", null);

            assertUnavailable(KE723);
            clock.advance(FlightService.PROVIDER_FAILURE_BACKOFF);
        }
    }

    @Test
    @DisplayName("이미 출발한 편은 TTL이 6시간이다")
    void pastFlightUsesLongTtl() {
        Instant afterDeparture = Instant.parse("2026-11-05T03:00:01Z");
        clock.set(afterDeparture);

        service.lookup(KE723, DATE);

        assertThat(savedEntry().getExpiresAt()).isEqualTo(afterDeparture.plus(Duration.ofHours(6)));
    }

    @Test
    @DisplayName("DB 캐시에서 읽은 구간 목록은 변경할 수 없다")
    void dbResultIsImmutable() {
        given(repository.findByFlightNoAndFlightDate("KE723", DATE)).willReturn(Optional.of(entry(
                clock.instant().plusSeconds(60))));

        List<FlightLeg> legs = service.lookup(KE723, DATE);

        assertThatThrownBy(() -> legs.add(ke723())).isInstanceOf(UnsupportedOperationException.class);
    }

    private void assertUnavailable(FlightNumber flightNumber) {
        assertThatThrownBy(() -> service.lookup(flightNumber, DATE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE);
    }

    private static boolean isBlocked(Thread thread) {
        return thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertNotFound() {
        assertThatThrownBy(() -> service.lookup(KE723, DATE))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.FLIGHT_NOT_FOUND);
    }

    private FlightCacheEntry savedEntry() {
        ArgumentCaptor<FlightCacheEntry> captor = ArgumentCaptor.forClass(FlightCacheEntry.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private FlightCacheEntry entry(Instant expiresAt) {
        return new FlightCacheEntry("KE723", DATE, objectMapper.writeValueAsString(List.of(ke723())),
                expiresAt.minus(Duration.ofHours(6)), expiresAt);
    }

    private static FlightLeg ke723() {
        return new FlightLeg(
                new FlightPoint("ICN", SEOUL, Instant.parse("2026-11-05T03:00:00Z"), null, "2"),
                new FlightPoint("KIX", TOKYO, Instant.parse("2026-11-05T04:45:00Z"),
                        Instant.parse("2026-11-05T04:52:00Z"), "1"),
                "Expected");
    }

    private static FlightLeg leg(String from, String departure, String to, String arrival) {
        return new FlightLeg(new FlightPoint(from, TOKYO, Instant.parse(departure), null, null),
                new FlightPoint(to, TOKYO, Instant.parse(arrival), null, null), "Expected");
    }

    private static final class FakeFlightClient implements FlightClient {

        List<FlightLeg> legs = List.of();
        FlightProviderException failure;
        final AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered;
        CountDownLatch release;

        @Override
        public List<FlightLeg> findLegs(FlightNumber flightNumber, LocalDate departureLocalDate) {
            calls.incrementAndGet();
            if (entered != null) {
                entered.countDown();
                await(release);
            }
            if (failure != null) {
                throw failure;
            }
            return legs;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            now = instant;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
