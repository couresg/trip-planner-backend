package com.tripplanner.trip.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.domain.Area;
import com.tripplanner.catalog.domain.City;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.catalog.repository.AreaRepository;
import com.tripplanner.catalog.service.CatalogService;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.client.AeroDataBoxParser;
import com.tripplanner.flight.client.FlightClient;
import com.tripplanner.flight.client.FlightProviderException;
import com.tripplanner.flight.client.MockFlightClient;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightPoint;
import com.tripplanner.flight.repository.FlightCacheRepository;
import com.tripplanner.flight.service.FlightService;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.api.ManualOverride;
import com.tripplanner.trip.api.TripDetailResponse;
import com.tripplanner.trip.api.TripSummaryResponse;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.FlightSource;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.repository.TripRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/** 항공편은 fixture(MockFlightClient)를 쓰는 실제 FlightService로, DB 계층은 목으로 대체한다. */
class TripServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 11, 5);
    private static final LocalDate END = LocalDate.of(2026, 11, 8);
    private static final Instant NOW = Instant.parse("2026-10-29T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final MockFlightClient FIXTURES =
            new MockFlightClient(new AeroDataBoxParser(JsonMapper.builder().build()));

    private final TripRepository tripRepository = mock(TripRepository.class);
    private final CatalogService catalogService = mock(CatalogService.class);
    private final AreaRepository areaRepository = mock(AreaRepository.class);
    private final AirportRepository airportRepository = mock(AirportRepository.class);
    private final OwnerTokenService ownerTokenService = new OwnerTokenService();
    private final List<String> lookups = new ArrayList<>();
    private FlightClient provider = FIXTURES;
    private TripService service;

    private City osaka;
    private Area namba;

    @BeforeEach
    void setUp() {
        osaka = city(1L, "OSA", "오사카");
        namba = area(11L, "난바", osaka);
        Area shinjuku = area(21L, "신주쿠", city(2L, "TYO", "도쿄"));
        Airport kix = mock(Airport.class);
        given(kix.getIata()).willReturn("KIX");
        given(kix.getTimezone()).willReturn("Asia/Tokyo");

        given(catalogService.getActiveCity("OSA")).willReturn(osaka);
        given(catalogService.getActiveCity("TYO"))
                .willThrow(new BusinessException(ErrorCode.CITY_NOT_FOUND, "지원하지 않는 도시입니다: TYO"));
        given(areaRepository.findById(11L)).willReturn(Optional.of(namba));
        given(areaRepository.findById(21L)).willReturn(Optional.of(shinjuku));
        given(areaRepository.findById(99L)).willReturn(Optional.empty());
        given(airportRepository.findAllByCityIdInOrderByIataAsc(List.of(1L))).willReturn(List.of(kix));
        given(tripRepository.save(any(Trip.class))).willAnswer(invocation -> invocation.getArgument(0));

        FlightClient counting = (flightNumber, date) -> {
            lookups.add(flightNumber.value() + "@" + date);
            return provider.findLegs(flightNumber, date);
        };
        FlightService flightService = new FlightService(counting, mock(FlightCacheRepository.class),
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
        service = new TripService(tripRepository, catalogService, areaRepository, airportRepository, flightService,
                ownerTokenService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ===== 생성 =====

    @Test
    @DisplayName("가는 편은 시작일, 오는 편은 종료일로 조회해 trip과 항공편 2건을 저장하고 토큰은 해시만 저장한다")
    void createWithLookup() {
        CreateTripResponse response = service.create(request("ke 0723", "KE724", null));

        Trip trip = savedTrip();
        assertThat(response.tripId()).isEqualTo(trip.getId().toString());
        assertThat(UUID.fromString(response.ownerToken()).toString()).isEqualTo(response.ownerToken());
        assertThat(trip.getOwnerTokenHash())
                .isEqualTo(ownerTokenService.hash(response.ownerToken()))
                .doesNotContain(response.ownerToken());
        assertThat(trip.getCity()).isSameAs(osaka);
        assertThat(trip.getArea()).isSameAs(namba);
        assertThat(trip.getCreatedAt()).isEqualTo(NOW);
        assertThat(lookups).containsExactly("KE723@2026-11-05", "KE724@2026-11-08");

        TripFlight outbound = trip.flight(FlightDirection.OUTBOUND);
        assertThat(outbound.getFlightNo()).isEqualTo("KE723");
        assertThat(outbound.getFlightDate()).isEqualTo(START);
        assertThat(outbound.getDepAirportIata()).isEqualTo("ICN");
        assertThat(outbound.getArrAirportIata()).isEqualTo("KIX");
        assertThat(outbound.getScheduledArrUtc()).isEqualTo(Instant.parse("2026-11-05T04:45:00Z"));
        assertThat(outbound.arrivalAt()).isEqualTo(Instant.parse("2026-11-05T04:52:00Z"));
        assertThat(outbound.getSource()).isEqualTo(FlightSource.AERODATABOX);
        assertThat(outbound.getStatus()).isEqualTo("Expected");
        assertThat(outbound.getFetchedAt()).isEqualTo(NOW);

        TripFlight returnFlight = trip.flight(FlightDirection.RETURN);
        assertThat(returnFlight.getFlightNo()).isEqualTo("KE724");
        assertThat(returnFlight.getFlightDate()).isEqualTo(END);
        assertThat(returnFlight.getDepAirportIata()).isEqualTo("KIX");
        assertThat(returnFlight.departureAt()).isEqualTo(Instant.parse("2026-11-08T06:00:00Z"));
    }

    @Test
    @DisplayName("시작일 22:30 출발해 다음 날 00:20 KIX에 도착하는 7C1302는 허용되고 도착 시각은 다음 날로 표시된다")
    void overnightArrival() {
        CreateTripResponse response = service.create(request("7C1302", "KE724", null));

        Trip trip = savedTrip();
        TripFlight outbound = trip.flight(FlightDirection.OUTBOUND);
        assertThat(outbound.getFlightDate()).isEqualTo(START);
        assertThat(outbound.getScheduledDepUtc()).isEqualTo(Instant.parse("2026-11-05T13:30:00Z"));
        assertThat(outbound.arrivalAt()).isEqualTo(Instant.parse("2026-11-05T15:20:00Z"));

        given(tripRepository.findWithDetailsById(trip.getId())).willReturn(Optional.of(trip));
        TripDetailResponse detail = service.get(response.tripId(), response.ownerToken());
        assertThat(detail.outbound().arrivalAt()).isEqualTo(OffsetDateTime.parse("2026-11-06T00:20:00+09:00"));
    }

    @Test
    @DisplayName("결항 편(LJ231)도 생성은 허용하고 status를 Canceled로 저장한다")
    void canceledFlightAllowed() {
        service.create(request("LJ231", "KE724", null));

        assertThat(savedTrip().flight(FlightDirection.OUTBOUND).getStatus()).isEqualTo("Canceled");
    }

    @Test
    @DisplayName("가는 편만 수동 입력하면 가는 편은 조회하지 않고 도착 지점만 저장하며, 오는 편은 조회한다")
    void manualOutboundOnly() {
        ManualOverride manual = new ManualOverride(OffsetDateTime.parse("2026-11-05T13:45:00+09:00"), "kix", null,
                null);

        service.create(request("ZZ9999", "KE724", manual));

        assertThat(lookups).containsExactly("KE724@2026-11-08");
        TripFlight outbound = savedTrip().flight(FlightDirection.OUTBOUND);
        assertThat(outbound.getFlightNo()).isEqualTo("ZZ9999");
        assertThat(outbound.getFlightDate()).isEqualTo(START);
        assertThat(outbound.getArrAirportIata()).isEqualTo("KIX");
        assertThat(outbound.arrivalAt()).isEqualTo(Instant.parse("2026-11-05T04:45:00Z"));
        assertThat(outbound.getDepAirportIata()).isNull();
        assertThat(outbound.getScheduledDepUtc()).isNull();
        assertThat(outbound.getSource()).isEqualTo(FlightSource.MANUAL);
        assertThat(outbound.getStatus()).isEqualTo("Manual");
        assertThat(savedTrip().flight(FlightDirection.RETURN).getSource()).isEqualTo(FlightSource.AERODATABOX);
    }

    @Test
    @DisplayName("오는 편만 수동 입력하면 가는 편만 조회하고, 오는 편은 출발 지점만 저장한다")
    void manualReturnOnly() {
        ManualOverride manual = new ManualOverride(null, null, OffsetDateTime.parse("2026-11-08T15:00:00+09:00"),
                "KIX");

        service.create(request("KE723", "ZZ9999", manual));

        assertThat(lookups).containsExactly("KE723@2026-11-05");
        TripFlight returnFlight = savedTrip().flight(FlightDirection.RETURN);
        assertThat(returnFlight.getDepAirportIata()).isEqualTo("KIX");
        assertThat(returnFlight.departureAt()).isEqualTo(Instant.parse("2026-11-08T06:00:00Z"));
        assertThat(returnFlight.getArrAirportIata()).isNull();
        assertThat(returnFlight.getScheduledArrUtc()).isNull();
        assertThat(returnFlight.getSource()).isEqualTo(FlightSource.MANUAL);
    }

    @Test
    @DisplayName("양방향 모두 수동 입력하면 항공편을 조회하지 않는다 (공급자 장애여도 생성된다)")
    void manualBoth() {
        provider = (flightNumber, date) -> {
            throw new FlightProviderException("down", null);
        };

        service.create(request("ZZ9999", "ZZ9998", manualBoth("2026-11-05T13:45:00+09:00",
                "2026-11-08T15:00:00+09:00")));

        assertThat(lookups).isEmpty();
        Trip trip = savedTrip();
        assertThat(trip.flight(FlightDirection.OUTBOUND).getSource()).isEqualTo(FlightSource.MANUAL);
        assertThat(trip.flight(FlightDirection.RETURN).getSource()).isEqualTo(FlightSource.MANUAL);
    }

    @Test
    @DisplayName("수동 가는 편 도착은 공항 현지 날짜 기준 시작일 또는 다음 날(자정 넘김)만 허용한다")
    void manualOutboundDateWindow() {
        service.create(request("ZZ9999", "KE724", manualBoth("2026-11-06T00:20:00+09:00",
                "2026-11-08T15:00:00+09:00")));
        // UTC로는 11-05 15:20이지만 KIX 현지로는 11-06 00:20 → 허용
        service.create(request("ZZ9999", "KE724", manualBoth("2026-11-05T15:20:00Z", "2026-11-08T15:00:00+09:00")));

        assertInvalidDateRange(request("ZZ9999", "KE724", manualBoth("2026-11-07T00:20:00+09:00",
                "2026-11-08T15:00:00+09:00")));
        assertInvalidDateRange(request("ZZ9999", "KE724", manualBoth("2026-11-04T23:59:00+09:00",
                "2026-11-08T15:00:00+09:00")));
    }

    @Test
    @DisplayName("수동 오는 편 출발은 공항 현지 날짜 기준 종료일과 같아야 한다")
    void manualReturnDate() {
        // UTC로는 11-07 23:30이지만 KIX 현지로는 11-08 08:30 → 허용
        service.create(request("ZZ9999", "ZZ9998", manualBoth("2026-11-05T13:45:00+09:00",
                "2026-11-07T23:30:00Z")));

        assertInvalidDateRange(request("ZZ9999", "ZZ9998", manualBoth("2026-11-05T13:45:00+09:00",
                "2026-11-09T00:10:00+09:00")));
        assertInvalidDateRange(request("ZZ9999", "ZZ9998", manualBoth("2026-11-05T13:45:00+09:00",
                "2026-11-07T23:50:00+09:00")));
    }

    @Test
    @DisplayName("수동 입력 공항이 도시 공항이 아니면 CITY_AIRPORT_MISMATCH이고 메시지에 공항을 적는다")
    void manualAirportMismatch() {
        ManualOverride outbound = new ManualOverride(OffsetDateTime.parse("2026-11-05T13:45:00+09:00"), "nrt", null,
                null);
        ManualOverride inbound = new ManualOverride(null, null, OffsetDateTime.parse("2026-11-08T15:00:00+09:00"),
                "HND");

        assertThatThrownBy(() -> service.create(request("ZZ9999", "KE724", outbound)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CITY_AIRPORT_MISMATCH);
                    assertThat(e.getMessage()).isEqualTo("선택한 여행 도시(오사카)와 도착 공항(NRT)이 다릅니다.");
                });
        assertThatThrownBy(() -> service.create(request("KE723", "ZZ9999", inbound)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CITY_AIRPORT_MISMATCH);
                    assertThat(e.getMessage()).isEqualTo("선택한 여행 도시(오사카)와 출발 공항(HND)이 다릅니다.");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("조회한 가는 편 도착 공항이 도시 공항이 아니면 CITY_AIRPORT_MISMATCH이고 도착 공항을 적는다")
    void lookupOutboundMismatch() {
        provider = (flightNumber, date) -> List.of(leg("ICN", SEOUL, "NRT", TOKYO, date));

        assertThatThrownBy(() -> service.create(request("KE701", "KE724", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CITY_AIRPORT_MISMATCH);
                    assertThat(e.getMessage()).isEqualTo("선택한 여행 도시(오사카)와 도착 공항(NRT)이 다릅니다.");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("조회한 오는 편 출발 공항이 도시 공항이 아니면 CITY_AIRPORT_MISMATCH이고 출발 공항을 적는다")
    void lookupReturnMismatch() {
        provider = (flightNumber, date) -> flightNumber.value().equals("KE702")
                ? List.of(leg("NRT", TOKYO, "ICN", SEOUL, date))
                : FIXTURES.findLegs(flightNumber, date);

        assertThatThrownBy(() -> service.create(request("KE723", "KE702", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CITY_AIRPORT_MISMATCH);
                    assertThat(e.getMessage()).isEqualTo("선택한 여행 도시(오사카)와 출발 공항(NRT)이 다릅니다.");
                });
    }

    @Test
    @DisplayName("다구간 편명(TW9901)은 도시 공항에 도착하는 구간(FUK→KIX)을 가는 편으로 고른다")
    void multiLegPicksCityLeg() {
        service.create(request("TW9901", "KE724", null));

        TripFlight outbound = savedTrip().flight(FlightDirection.OUTBOUND);
        assertThat(outbound.getDepAirportIata()).isEqualTo("FUK");
        assertThat(outbound.getArrAirportIata()).isEqualTo("KIX");
    }

    @Test
    @DisplayName("찾지 못한 편명은 FLIGHT_NOT_FOUND이고 저장하지 않는다")
    void flightNotFound() {
        assertThatThrownBy(() -> service.create(request("ZZ9999", "KE724", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FLIGHT_NOT_FOUND));
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("공급자 장애는 FLIGHT_PROVIDER_UNAVAILABLE로 전달된다")
    void providerUnavailable() {
        provider = (flightNumber, date) -> {
            throw new FlightProviderException("AeroDataBox HTTP 503", null);
        };

        assertThatThrownBy(() -> service.create(request("KE723", "KE724", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FLIGHT_PROVIDER_UNAVAILABLE));
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("종료일이 시작일보다 빠르거나 기간이 30일을 넘으면 INVALID_DATE_RANGE이고 조회하지 않는다")
    void dateRange() {
        assertInvalidDateRange(request("KE723", "KE724", START, START.minusDays(1), null));
        assertInvalidDateRange(request("KE723", "KE724", START, START.plusDays(31), null));
        assertThat(lookups).isEmpty();

        service.create(request("KE723", "KE724", START, START.plusDays(30), null));
        service.create(request("KE723", "KE724", START, START, null));
    }

    @Test
    @DisplayName("당일 여행에서 가는 편이 자정 넘어 도착하고 수동 오는 편이 그보다 먼저 출발하면 INVALID_DATE_RANGE")
    void returnBeforeOvernightArrivalManual() {
        ManualOverride manual = new ManualOverride(null, null, OffsetDateTime.parse("2026-11-05T23:00:00+09:00"),
                "KIX");

        assertThatThrownBy(() -> service.create(request("7C1302", "ZZ9999", START, START, manual)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_DATE_RANGE);
                    assertThat(e.getMessage()).isEqualTo(
                            "오는 편 출발(현지 2026-11-05 23:00)이 가는 편 도착(현지 2026-11-06 00:20)보다 빠릅니다.");
                });
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("오는 편 출발이 가는 편 도착보다 뒤가 아니면 조회·수동 조합 모두 INVALID_DATE_RANGE")
    void returnNotAfterOutboundAllCombinations() {
        // 조회 + 조회: 7C1302 도착 11-06 00:20, KE724 출발 11-05 15:00
        assertInvalidDateRange(request("7C1302", "KE724", START, START, null));
        // 수동 + 조회: 도착 11-05 16:00, KE724 출발 11-05 15:00
        assertInvalidDateRange(request("ZZ9999", "KE724", START, START,
                new ManualOverride(OffsetDateTime.parse("2026-11-05T16:00:00+09:00"), "KIX", null, null)));
        // 수동 + 수동: 출발 = 도착 (같은 시각도 거부)
        assertInvalidDateRange(request("ZZ9999", "ZZ9998", START, START,
                manualBoth("2026-11-05T14:00:00+09:00", "2026-11-05T14:00:00+09:00")));
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("당일 여행이라도 오는 편 출발이 가는 편 도착보다 뒤면 생성된다")
    void sameDayTripInOrder() {
        // KE723 도착(revised) 11-05 13:52, KE724 출발 11-05 15:00
        service.create(request("KE723", "KE724", START, START, null));
        service.create(request("ZZ9999", "ZZ9998", START, START,
                manualBoth("2026-11-05T14:00:00+09:00", "2026-11-05T14:01:00+09:00")));

        verify(tripRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("지역이 없거나 선택한 도시의 지역이 아니면 VALIDATION_FAILED")
    void areaNotInCity() {
        for (long areaId : new long[] {21L, 99L}) {
            CreateTripRequest request = new CreateTripRequest("KE723", "KE724", "OSA", areaId, START, END, null);
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThat(lookups).isEmpty();
    }

    @Test
    @DisplayName("지원하지 않는 도시는 CITY_NOT_FOUND")
    void cityNotFound() {
        CreateTripRequest request = new CreateTripRequest("KE723", "KE724", "TYO", 11L, START, END, null);

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CITY_NOT_FOUND));
    }

    @Test
    @DisplayName("편명 형식이 틀리거나 비어 있으면 수동 입력이어도 INVALID_FLIGHT_NO")
    void invalidFlightNo() {
        ManualOverride manual = manualBoth("2026-11-05T13:45:00+09:00", "2026-11-08T15:00:00+09:00");

        for (CreateTripRequest request : List.of(request("KE72345", "KE724", null), request("KE723", null, manual),
                request("", "KE724", manual))) {
            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_FLIGHT_NO));
        }
        verifyNoInteractions(catalogService);
    }

    // ===== 목록 =====

    @Test
    @DisplayName("목록은 콤마로 구분한 토큰의 해시로 조회하고 빈 항목·중복은 무시한다")
    void listHashesTokens() {
        given(tripRepository.findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(anyCollection()))
                .willReturn(List.of());

        service.list(" token-a, ,token-b,,token-a ");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(tripRepository).findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(captor.capture());
        assertThat(captor.getValue())
                .containsExactly(ownerTokenService.hash("token-a"), ownerTokenService.hash("token-b"));
    }

    @Test
    @DisplayName("헤더가 없거나 토큰이 하나도 없으면 조회 없이 빈 목록")
    void listWithoutTokens() {
        assertThat(service.list(null)).isEmpty();
        assertThat(service.list(" , ,")).isEmpty();
        verifyNoInteractions(tripRepository);
    }

    @Test
    @DisplayName("토큰이 50개를 넘으면 VALIDATION_FAILED")
    void listTooManyTokens() {
        String fifty = String.join(",", IntStream.range(0, 50).mapToObj(i -> "t" + i).toList());
        given(tripRepository.findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(anyCollection()))
                .willReturn(List.of());

        assertThat(service.list(fifty)).isEmpty();
        assertThatThrownBy(() -> service.list(fifty + ",t50"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("목록 항목은 도시·지역·기간·편명을 담는다")
    void listMapsSummary() {
        CreateTripResponse created = service.create(request("KE723", "KE724", null));
        Trip trip = savedTrip();
        given(tripRepository.findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(anyCollection()))
                .willReturn(List.of(trip));

        List<TripSummaryResponse> trips = service.list(created.ownerToken());

        assertThat(trips).containsExactly(new TripSummaryResponse(created.tripId(),
                new TripSummaryResponse.CityRef("OSA", "오사카"), new TripSummaryResponse.AreaRef(11L, "난바"),
                START, END, "KE723", "KE724"));
    }

    // ===== 상세·삭제 =====

    @Test
    @DisplayName("상세는 도착·출발 시각을 공항 현지 오프셋으로, source·status와 함께 반환한다")
    void getDetail() {
        CreateTripResponse created = service.create(request("KE723", "ZZ9999",
                new ManualOverride(null, null, OffsetDateTime.parse("2026-11-08T06:00:00Z"), "KIX")));
        Trip trip = savedTrip();
        given(tripRepository.findWithDetailsById(trip.getId())).willReturn(Optional.of(trip));

        TripDetailResponse detail = service.get(created.tripId(), " " + created.ownerToken() + " ");

        assertThat(detail.tripId()).isEqualTo(created.tripId());
        assertThat(detail.city()).isEqualTo(new TripDetailResponse.CityInfo("OSA", "오사카", "Asia/Tokyo"));
        assertThat(detail.area()).isEqualTo(new TripDetailResponse.AreaInfo(11L, "난바", 34.6664, 135.5011));
        assertThat(detail.startDate()).isEqualTo(START);
        assertThat(detail.endDate()).isEqualTo(END);
        assertThat(detail.outbound()).isEqualTo(new TripDetailResponse.Outbound("KE723", "KIX",
                OffsetDateTime.parse("2026-11-05T13:52:00+09:00"), FlightSource.AERODATABOX, "Expected"));
        assertThat(detail.returnFlight()).isEqualTo(new TripDetailResponse.Return("ZZ9999", "KIX",
                OffsetDateTime.parse("2026-11-08T15:00:00+09:00"), FlightSource.MANUAL, "Manual"));
    }

    @Test
    @DisplayName("없는 여행·형식이 틀린 ID는 TRIP_NOT_FOUND, 토큰이 없거나 다르면 FORBIDDEN_TRIP")
    void getAccessRules() {
        CreateTripResponse created = service.create(request("KE723", "KE724", null));
        Trip trip = savedTrip();
        given(tripRepository.findWithDetailsById(trip.getId())).willReturn(Optional.of(trip));

        assertErrorCode(() -> service.get(UUID.randomUUID().toString(), created.ownerToken()),
                ErrorCode.TRIP_NOT_FOUND);
        assertErrorCode(() -> service.get("not-a-uuid", created.ownerToken()), ErrorCode.TRIP_NOT_FOUND);
        assertErrorCode(() -> service.get(created.tripId(), null), ErrorCode.FORBIDDEN_TRIP);
        assertErrorCode(() -> service.get(created.tripId(), ""), ErrorCode.FORBIDDEN_TRIP);
        assertErrorCode(() -> service.get(created.tripId(), ownerTokenService.issue()), ErrorCode.FORBIDDEN_TRIP);
        assertErrorCode(() -> service.get(created.tripId(), trip.getOwnerTokenHash()), ErrorCode.FORBIDDEN_TRIP);
    }

    @Test
    @DisplayName("삭제는 토큰이 맞을 때만 수행한다")
    void delete() {
        CreateTripResponse created = service.create(request("KE723", "KE724", null));
        Trip trip = savedTrip();
        given(tripRepository.findWithDetailsById(trip.getId())).willReturn(Optional.of(trip));

        assertErrorCode(() -> service.delete(created.tripId(), ownerTokenService.issue()), ErrorCode.FORBIDDEN_TRIP);
        assertErrorCode(() -> service.delete("1234", created.ownerToken()), ErrorCode.TRIP_NOT_FOUND);
        verify(tripRepository, never()).delete(any());

        service.delete(created.tripId(), created.ownerToken());

        verify(tripRepository).delete(trip);
    }

    // ===== 헬퍼 =====

    private Trip savedTrip() {
        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private void assertInvalidDateRange(CreateTripRequest request) {
        assertErrorCode(() -> service.create(request), ErrorCode.INVALID_DATE_RANGE);
    }

    private static void assertErrorCode(ThrowingCallable call,
            ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static CreateTripRequest request(String outbound, String inbound, ManualOverride manual) {
        return request(outbound, inbound, START, END, manual);
    }

    private static CreateTripRequest request(String outbound, String inbound, LocalDate start, LocalDate end,
            ManualOverride manual) {
        return new CreateTripRequest(outbound, inbound, "OSA", 11L, start, end, manual);
    }

    private static ManualOverride manualBoth(String arrivalAt, String departureAt) {
        return new ManualOverride(OffsetDateTime.parse(arrivalAt), "KIX", OffsetDateTime.parse(departureAt), "KIX");
    }

    private static FlightLeg leg(String from, ZoneId fromZone, String to, ZoneId toZone, LocalDate date) {
        Instant departure = date.atTime(10, 0).atZone(fromZone).toInstant();
        return new FlightLeg(new FlightPoint(from, fromZone, departure, null, null),
                new FlightPoint(to, toZone, departure.plusSeconds(2 * 3600), null, null), "Expected");
    }

    private static City city(Long id, String code, String nameKo) {
        City city = mock(City.class);
        given(city.getId()).willReturn(id);
        given(city.getCode()).willReturn(code);
        given(city.getNameKo()).willReturn(nameKo);
        given(city.getTimezone()).willReturn("Asia/Tokyo");
        return city;
    }

    private static Area area(Long id, String nameKo, City city) {
        Area area = mock(Area.class);
        given(area.getId()).willReturn(id);
        given(area.getNameKo()).willReturn(nameKo);
        given(area.getCity()).willReturn(city);
        given(area.getLat()).willReturn(new BigDecimal("34.666400"));
        given(area.getLng()).willReturn(new BigDecimal("135.501100"));
        return area;
    }
}
