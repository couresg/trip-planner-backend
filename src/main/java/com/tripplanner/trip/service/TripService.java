package com.tripplanner.trip.service;

import com.tripplanner.catalog.domain.Airport;
import com.tripplanner.catalog.domain.Area;
import com.tripplanner.catalog.domain.City;
import com.tripplanner.catalog.repository.AirportRepository;
import com.tripplanner.catalog.repository.AreaRepository;
import com.tripplanner.catalog.service.CatalogService;
import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.flight.domain.FlightLeg;
import com.tripplanner.flight.domain.FlightNumber;
import com.tripplanner.flight.service.FlightService;
import com.tripplanner.trip.api.CreateTripRequest;
import com.tripplanner.trip.api.CreateTripResponse;
import com.tripplanner.trip.api.ManualOverride;
import com.tripplanner.trip.api.TripDetailResponse;
import com.tripplanner.trip.api.TripSummaryResponse;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.domain.TripFlight;
import com.tripplanner.trip.repository.TripRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 여행 생성·조회·삭제. 생성은 외부 항공편 조회를 먼저 끝낸 뒤 저장한다
 * (외부 API 호출 동안 DB 커넥션·트랜잭션을 잡지 않도록 create에는 트랜잭션을 걸지 않고,
 * trip + trip_flight 2행은 cascade로 tripRepository.save 한 트랜잭션에서 저장된다).
 */
@Service
public class TripService {

    /** 여행 기간 상한: endDate - startDate (일) */
    static final long MAX_TRIP_DAYS = 30;
    /** GET /trips 헤더 하나에 담을 수 있는 소유 토큰 수 */
    static final int MAX_OWNER_TOKENS = 50;
    private static final DateTimeFormatter LOCAL_TEXT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final TripRepository tripRepository;
    private final CatalogService catalogService;
    private final AreaRepository areaRepository;
    private final AirportRepository airportRepository;
    private final FlightService flightService;
    private final OwnerTokenService ownerTokenService;
    private final Clock clock;

    public TripService(TripRepository tripRepository, CatalogService catalogService, AreaRepository areaRepository,
            AirportRepository airportRepository, FlightService flightService, OwnerTokenService ownerTokenService,
            Clock clock) {
        this.tripRepository = tripRepository;
        this.catalogService = catalogService;
        this.areaRepository = areaRepository;
        this.airportRepository = airportRepository;
        this.flightService = flightService;
        this.ownerTokenService = ownerTokenService;
        this.clock = clock;
    }

    /**
     * @throws BusinessException INVALID_FLIGHT_NO, INVALID_DATE_RANGE, CITY_NOT_FOUND, VALIDATION_FAILED(도시 밖 지역),
     *                           CITY_AIRPORT_MISMATCH, FLIGHT_NOT_FOUND, FLIGHT_PROVIDER_UNAVAILABLE
     */
    public CreateTripResponse create(CreateTripRequest request) {
        FlightNumber outboundNo = FlightNumber.of(request.outboundFlightNo());
        FlightNumber returnNo = FlightNumber.of(request.returnFlightNo());
        LocalDate startDate = request.startDate();
        LocalDate endDate = request.endDate();
        validateDateRange(startDate, endDate);

        City city = catalogService.getActiveCity(request.cityCode());
        Area area = areaRepository.findById(request.areaId())
                .filter(a -> a.getCity().getId().equals(city.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "선택한 도시(" + city.getNameKo() + ")의 숙소 지역이 아닙니다."));
        Map<String, Airport> airports = airportsByIata(city);

        Instant now = clock.instant();
        ManualOverride manual = request.manualOverride();
        TripFlight outbound = manual != null && manual.hasOutbound()
                ? manualOutbound(outboundNo, startDate, manual, city, airports, now)
                : lookupOutbound(outboundNo, startDate, city, airports, now);
        TripFlight returnFlight = manual != null && manual.hasReturn()
                ? manualReturn(returnNo, endDate, manual, city, airports, now)
                : lookupReturn(returnNo, endDate, city, airports, now);
        validateFlightOrder(outbound, returnFlight, airports);

        String ownerToken = ownerTokenService.issue();
        Trip trip = new Trip(ownerTokenService.hash(ownerToken), city, area, startDate, endDate, now);
        trip.addFlight(outbound);
        trip.addFlight(returnFlight);
        tripRepository.save(trip);
        return new CreateTripResponse(trip.getId().toString(), ownerToken);
    }

    /** 헤더 값은 콤마로 구분한 토큰 목록. 빈 항목은 무시하고, 모르는 토큰은 결과에서 빠질 뿐이다. */
    @Transactional(readOnly = true)
    public List<TripSummaryResponse> list(String ownerTokensHeader) {
        if (ownerTokensHeader == null) {
            return List.of();
        }
        List<String> tokens = Arrays.stream(ownerTokensHeader.split(","))
                .map(String::strip)
                .filter(token -> !token.isEmpty())
                .distinct()
                .toList();
        if (tokens.isEmpty()) {
            return List.of();
        }
        if (tokens.size() > MAX_OWNER_TOKENS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "소유 토큰은 한 번에 최대 " + MAX_OWNER_TOKENS + "개까지 보낼 수 있습니다.");
        }
        List<String> hashes = tokens.stream().map(ownerTokenService::hash).toList();
        return tripRepository.findAllByOwnerTokenHashInOrderByStartDateAscCreatedAtAsc(hashes).stream()
                .map(TripService::toSummary)
                .toList();
    }

    /** @throws BusinessException TRIP_NOT_FOUND(없거나 형식이 틀린 ID), FORBIDDEN_TRIP(토큰 누락·불일치) */
    @Transactional(readOnly = true)
    public TripDetailResponse get(String tripId, String ownerToken) {
        Trip trip = findOwned(tripId, ownerToken);
        Map<String, ZoneId> zones = airportsByIata(trip.getCity()).values().stream()
                .collect(Collectors.toMap(Airport::getIata, a -> ZoneId.of(a.getTimezone())));
        ZoneId cityZone = ZoneId.of(trip.getCity().getTimezone());
        TripFlight outbound = trip.flight(FlightDirection.OUTBOUND);
        TripFlight returnFlight = trip.flight(FlightDirection.RETURN);
        City city = trip.getCity();
        Area area = trip.getArea();
        return new TripDetailResponse(
                trip.getId().toString(),
                new TripDetailResponse.CityInfo(city.getCode(), city.getNameKo(), city.getTimezone()),
                new TripDetailResponse.AreaInfo(area.getId(), area.getNameKo(), area.getLat().doubleValue(),
                        area.getLng().doubleValue()),
                trip.getStartDate(),
                trip.getEndDate(),
                new TripDetailResponse.Outbound(outbound.getFlightNo(), outbound.getArrAirportIata(),
                        local(outbound.arrivalAt(), zones.getOrDefault(outbound.getArrAirportIata(), cityZone)),
                        outbound.getSource(), outbound.getStatus()),
                new TripDetailResponse.Return(returnFlight.getFlightNo(), returnFlight.getDepAirportIata(),
                        local(returnFlight.departureAt(),
                                zones.getOrDefault(returnFlight.getDepAirportIata(), cityZone)),
                        returnFlight.getSource(), returnFlight.getStatus()));
    }

    /** trip_flight는 cascade(JPA + FK ON DELETE CASCADE)로 함께 삭제된다. */
    @Transactional
    public void delete(String tripId, String ownerToken) {
        tripRepository.delete(findOwned(tripId, ownerToken));
    }

    private Trip findOwned(String tripId, String ownerToken) {
        Trip trip = parseId(tripId)
                .flatMap(tripRepository::findWithDetailsById)
                .orElseThrow(() -> new BusinessException(ErrorCode.TRIP_NOT_FOUND, "여행을 찾을 수 없습니다."));
        String token = ownerToken == null ? null : ownerToken.strip();
        if (!ownerTokenService.matches(token, trip.getOwnerTokenHash())) {
            throw new BusinessException(ErrorCode.FORBIDDEN_TRIP, "이 여행에 접근할 권한이 없습니다.");
        }
        return trip;
    }

    private static Optional<UUID> parseId(String tripId) {
        try {
            return Optional.of(UUID.fromString(tripId));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    private static void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (endDate.isBefore(startDate)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE, "종료일이 시작일보다 빠릅니다.");
        }
        if (ChronoUnit.DAYS.between(startDate, endDate) > MAX_TRIP_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "여행 기간은 최대 " + MAX_TRIP_DAYS + "일입니다.");
        }
    }

    /** 오는 편 출발(revised ?? scheduled)은 가는 편 도착보다 뒤여야 한다. 수동·조회 조합 모두 같은 규칙이다. */
    private static void validateFlightOrder(TripFlight outbound, TripFlight returnFlight,
            Map<String, Airport> airports) {
        Instant arrivalAt = outbound.arrivalAt();
        Instant departureAt = returnFlight.departureAt();
        if (departureAt.isAfter(arrivalAt)) {
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                "오는 편 출발(현지 " + localText(departureAt, airports.get(returnFlight.getDepAirportIata()))
                        + ")이 가는 편 도착(현지 " + localText(arrivalAt, airports.get(outbound.getArrAirportIata()))
                        + ")보다 빠릅니다.");
    }

    private static String localText(Instant instant, Airport airport) {
        return LOCAL_TEXT.format(instant.atZone(ZoneId.of(airport.getTimezone())));
    }

    private Map<String, Airport> airportsByIata(City city) {
        return airportRepository.findAllByCityIdInOrderByIataAsc(List.of(city.getId())).stream()
                .collect(Collectors.toMap(Airport::getIata, Function.identity()));
    }

    private TripFlight lookupOutbound(FlightNumber flightNo, LocalDate startDate, City city,
            Map<String, Airport> airports, Instant now) {
        List<FlightLeg> legs = flightService.lookup(flightNo, startDate);
        FlightLeg leg = flightService.findArrivingAt(legs, airports.keySet())
                .orElseThrow(() -> mismatch(city, "도착", legs.getLast().arrival().iata()));
        return TripFlight.fromLeg(FlightDirection.OUTBOUND, flightNo.value(), startDate, leg, now);
    }

    private TripFlight lookupReturn(FlightNumber flightNo, LocalDate endDate, City city,
            Map<String, Airport> airports, Instant now) {
        List<FlightLeg> legs = flightService.lookup(flightNo, endDate);
        FlightLeg leg = flightService.findDepartingFrom(legs, airports.keySet())
                .orElseThrow(() -> mismatch(city, "출발", legs.getFirst().departure().iata()));
        return TripFlight.fromLeg(FlightDirection.RETURN, flightNo.value(), endDate, leg, now);
    }

    /** 수동 가는 편: 도착 현지 날짜는 시작일 또는 다음 날(자정 넘김 도착)이어야 한다. */
    private static TripFlight manualOutbound(FlightNumber flightNo, LocalDate startDate, ManualOverride manual,
            City city, Map<String, Airport> airports, Instant now) {
        Airport airport = cityAirport(manual.outboundArrivalAirport(), city, airports, "도착");
        Instant arrivalAt = manual.outboundArrivalAt().toInstant();
        LocalDate arrivalDate = arrivalAt.atZone(ZoneId.of(airport.getTimezone())).toLocalDate();
        if (!arrivalDate.equals(startDate) && !arrivalDate.equals(startDate.plusDays(1))) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "가는 편 도착일(현지 " + arrivalDate + ")은 시작일 또는 그다음 날이어야 합니다.");
        }
        return TripFlight.manualOutbound(flightNo.value(), startDate, airport.getIata(), arrivalAt, now);
    }

    /** 수동 오는 편: 출발 현지 날짜는 종료일과 같아야 한다. */
    private static TripFlight manualReturn(FlightNumber flightNo, LocalDate endDate, ManualOverride manual,
            City city, Map<String, Airport> airports, Instant now) {
        Airport airport = cityAirport(manual.returnDepartureAirport(), city, airports, "출발");
        Instant departureAt = manual.returnDepartureAt().toInstant();
        LocalDate departureDate = departureAt.atZone(ZoneId.of(airport.getTimezone())).toLocalDate();
        if (!departureDate.equals(endDate)) {
            throw new BusinessException(ErrorCode.INVALID_DATE_RANGE,
                    "오는 편 출발일(현지 " + departureDate + ")은 종료일과 같아야 합니다.");
        }
        return TripFlight.manualReturn(flightNo.value(), endDate, airport.getIata(), departureAt, now);
    }

    private static Airport cityAirport(String rawIata, City city, Map<String, Airport> airports, String role) {
        String iata = rawIata.strip().toUpperCase(Locale.ROOT);
        Airport airport = airports.get(iata);
        if (airport == null) {
            throw mismatch(city, role, iata);
        }
        return airport;
    }

    private static BusinessException mismatch(City city, String role, String iata) {
        return new BusinessException(ErrorCode.CITY_AIRPORT_MISMATCH,
                "선택한 여행 도시(" + city.getNameKo() + ")와 " + role + " 공항(" + iata + ")이 다릅니다.");
    }

    private static OffsetDateTime local(Instant instant, ZoneId zone) {
        return instant.atZone(zone).toOffsetDateTime();
    }

    private static TripSummaryResponse toSummary(Trip trip) {
        return new TripSummaryResponse(
                trip.getId().toString(),
                new TripSummaryResponse.CityRef(trip.getCity().getCode(), trip.getCity().getNameKo()),
                new TripSummaryResponse.AreaRef(trip.getArea().getId(), trip.getArea().getNameKo()),
                trip.getStartDate(),
                trip.getEndDate(),
                trip.flight(FlightDirection.OUTBOUND).getFlightNo(),
                trip.flight(FlightDirection.RETURN).getFlightNo());
    }
}
