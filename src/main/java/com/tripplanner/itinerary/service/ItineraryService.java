package com.tripplanner.itinerary.service;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import com.tripplanner.itinerary.api.AddItineraryItemRequest;
import com.tripplanner.itinerary.api.ItineraryItemResponse;
import com.tripplanner.itinerary.api.ItineraryResponse;
import com.tripplanner.itinerary.api.UpdateItineraryItemRequest;
import com.tripplanner.itinerary.domain.ItineraryItem;
import com.tripplanner.itinerary.repository.ItineraryItemRepository;
import com.tripplanner.place.domain.Place;
import com.tripplanner.place.repository.PlaceRepository;
import com.tripplanner.transfer.api.ArrivalTransferResponse;
import com.tripplanner.transfer.api.DepartureTransferResponse;
import com.tripplanner.transfer.service.ArrivalTransferService;
import com.tripplanner.transfer.service.DepartureTransferService;
import com.tripplanner.trip.domain.FlightDirection;
import com.tripplanner.trip.domain.Trip;
import com.tripplanner.trip.service.FlightRefreshService.FlightRefresh;
import com.tripplanner.trip.service.TripService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일자별 일정 (F4). 변경은 여행 행에 쓰기 잠금을 잡아 같은 여행의 동시 변경을 직렬화한다.
 * earliestStart·latestEnd는 도착일·귀국일 탭과 같은 교통편 계산에서 가져오고, 계산 실패는 그대로 전파한다.
 */
@Service
@EnableConfigurationProperties(ItineraryProperties.class)
public class ItineraryService {

    private static final DateTimeFormatter PLANNED_TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TripService tripService;
    private final ItineraryItemRepository itemRepository;
    private final PlaceRepository placeRepository;
    private final ArrivalTransferService arrivalTransferService;
    private final DepartureTransferService departureTransferService;
    private final ItineraryWarningCalculator warningCalculator;
    private final ItineraryProperties properties;
    private final Clock clock;

    public ItineraryService(TripService tripService, ItineraryItemRepository itemRepository,
            PlaceRepository placeRepository, ArrivalTransferService arrivalTransferService,
            DepartureTransferService departureTransferService, ItineraryWarningCalculator warningCalculator,
            ItineraryProperties properties, Clock clock) {
        this.tripService = tripService;
        this.itemRepository = itemRepository;
        this.placeRepository = placeRepository;
        this.arrivalTransferService = arrivalTransferService;
        this.departureTransferService = departureTransferService;
        this.warningCalculator = warningCalculator;
        this.properties = properties;
        this.clock = clock;
    }

    /** flightRefresh: 호출 전 두 편 재조회 결과 (교통편 계산에 넘기고 응답에는 담지 않는다). @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP */
    @Transactional(readOnly = true)
    public ItineraryResponse get(String tripId, String ownerToken, int immigrationMin, int airportBufferMin,
            FlightRefresh flightRefresh) {
        Trip trip = tripService.findOwned(tripId, ownerToken);
        OffsetDateTime earliest = earliestStart(arrivalTransferService.calculate(trip, immigrationMin,
                flightRefresh.of(FlightDirection.OUTBOUND)));
        OffsetDateTime latest = latestEnd(departureTransferService.calculate(trip, airportBufferMin,
                flightRefresh.of(FlightDirection.RETURN)));
        ZoneId zone = ZoneId.of(trip.getCity().getTimezone());

        Map<LocalDate, List<ItineraryItem>> itemsByDate = itemRepository
                .findAllByTripOrderByVisitDateAscPositionAscIdAsc(trip).stream()
                .collect(Collectors.groupingBy(ItineraryItem::getVisitDate));
        AtomicInteger dayIndex = new AtomicInteger();
        List<ItineraryResponse.Day> days = trip.getStartDate().datesUntil(trip.getEndDate().plusDays(1))
                .map(date -> new ItineraryResponse.Day(date, dayIndex.incrementAndGet(),
                        ifOnDate(earliest, date, zone), ifOnDate(latest, date, zone),
                        itemsByDate.getOrDefault(date, List.of()).stream()
                                .map(item -> toDayItem(item, earliest, latest, zone))
                                .toList()))
                .toList();
        return new ItineraryResponse(zone.getId(), properties.returnMarginMin(), days);
    }

    /** @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP, DATE_OUT_OF_TRIP, PLACE_NOT_FOUND, ITINERARY_DAY_FULL */
    @Transactional
    public ItineraryItemResponse addItem(String tripId, String ownerToken, AddItineraryItemRequest request) {
        Trip trip = tripService.findOwnedForUpdate(tripId, ownerToken);
        requireInTrip(trip, request.date());
        Place place = placeRepository.findById(request.placeId())
                .filter(p -> p.getCity().getId().equals(trip.getCity().getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLACE_NOT_FOUND, "장소를 찾을 수 없습니다."));
        // 잠금 아래에서 날짜의 position은 0부터 빈틈이 없으므로 다음 위치가 곧 현재 항목 수다.
        int position = itemRepository.findMaxPosition(trip, request.date()) + 1;
        requireDayCapacity(position + 1);
        ItineraryItem item = itemRepository.save(new ItineraryItem(trip, place, request.date(), position,
                parseTime(request.plannedTime()), request.memo() == null ? "" : request.memo(), clock.instant()));
        return toResponse(item);
    }

    /** @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP, ITINERARY_ITEM_NOT_FOUND */
    @Transactional
    public ItineraryItemResponse updateItem(String tripId, String ownerToken, long itemId,
            UpdateItineraryItemRequest request) {
        Trip trip = tripService.findOwnedForUpdate(tripId, ownerToken);
        ItineraryItem item = findItem(itemId, trip);
        item.edit(parseTime(request.plannedTime()), request.memo(), clock.instant());
        return toResponse(item);
    }

    /**
     * 날짜의 순서를 itemIds로 정한다. 다른 날짜의 항목은 이 날짜로 옮기고 그 날짜는 0부터 다시 매긴다.
     * 이 날짜에 이미 있지만 목록에 없는 항목은 기존 순서대로 뒤에 붙인다.
     *
     * @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP, DATE_OUT_OF_TRIP, ITINERARY_ITEM_NOT_FOUND,
     *                           ITINERARY_DAY_FULL
     */
    @Transactional
    public void reorder(String tripId, String ownerToken, LocalDate date, List<Long> itemIds) {
        Trip trip = tripService.findOwnedForUpdate(tripId, ownerToken);
        requireInTrip(trip, date);
        List<ItineraryItem> all = itemRepository.findAllByTripOrderByVisitDateAscPositionAscIdAsc(trip);
        Map<Long, ItineraryItem> byId = all.stream()
                .collect(Collectors.toMap(ItineraryItem::getId, Function.identity()));

        List<ItineraryItem> ordered = new ArrayList<>();
        for (Long itemId : itemIds) {
            ItineraryItem item = byId.get(itemId);
            if (item == null) {
                throw itemNotFound();
            }
            ordered.add(item);
        }
        Set<LocalDate> sourceDates = ordered.stream()
                .map(ItineraryItem::getVisitDate)
                .filter(source -> !source.equals(date))
                .collect(Collectors.toSet());
        Set<Long> listed = new HashSet<>(itemIds);
        all.stream()
                .filter(item -> item.getVisitDate().equals(date) && !listed.contains(item.getId()))
                .forEach(ordered::add);
        requireDayCapacity(ordered.size());

        Instant now = clock.instant();
        assignPositions(ordered, date, now);
        for (LocalDate source : sourceDates) {
            assignPositions(all.stream().filter(item -> item.getVisitDate().equals(source)).toList(), source, now);
        }
    }

    /** @throws BusinessException TRIP_NOT_FOUND, FORBIDDEN_TRIP, ITINERARY_ITEM_NOT_FOUND */
    @Transactional
    public void deleteItem(String tripId, String ownerToken, long itemId) {
        Trip trip = tripService.findOwnedForUpdate(tripId, ownerToken);
        ItineraryItem item = findItem(itemId, trip);
        LocalDate date = item.getVisitDate();
        itemRepository.delete(item);
        // 조회 전 자동 flush로 삭제가 먼저 반영된다.
        assignPositions(itemRepository.findAllByTripAndVisitDateOrderByPositionAscIdAsc(trip, date), date,
                clock.instant());
    }

    /**
     * 도착일 탭 타임라인의 숙소 도착과 같은 값: 노선별 추천편 중 숙소에 가장 먼저 도착하는 시각.
     * 추천편이 없으면 입국 완료 + 택시(소요 + 도보, 여럿이면 가장 빠른 쪽), 택시도 없으면 입국 완료 시각이다.
     */
    static OffsetDateTime earliestStart(ArrivalTransferResponse arrival) {
        return arrival.options().stream()
                .flatMap(option -> option.departures().stream())
                .filter(ArrivalTransferResponse.Departure::recommended)
                .map(ArrivalTransferResponse.Departure::arriveAreaAt)
                .min(Comparator.naturalOrder())
                .or(() -> arrival.options().stream()
                        .filter(option -> option.mode().isOnDemand())
                        .map(option -> arrival.readyAt().plusMinutes(option.durationMin() + option.walkAfterMin()))
                        .min(Comparator.naturalOrder()))
                .orElse(arrival.readyAt());
    }

    /**
     * 귀국일 탭 강조 문구와 같은 값: latestLeaveAt, 없으면 택시·차량 호출 leaveByAt 중 가장 이른 시각.
     * 착륙 후 시간이 없으면(noTimeAfterLanding) null이다.
     */
    static OffsetDateTime latestEnd(DepartureTransferResponse departure) {
        if (departure.noTimeAfterLanding()) {
            return null;
        }
        if (departure.latestLeaveAt() != null) {
            return departure.latestLeaveAt();
        }
        return departure.options().stream()
                .map(DepartureTransferResponse.Option::leaveByAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    private static OffsetDateTime ifOnDate(OffsetDateTime at, LocalDate date, ZoneId zone) {
        return at != null && at.atZoneSameInstant(zone).toLocalDate().equals(date) ? at : null;
    }

    private ItineraryResponse.Item toDayItem(ItineraryItem item, OffsetDateTime earliest, OffsetDateTime latest,
            ZoneId zone) {
        Place place = item.getPlace();
        ItineraryWarningCalculator.Visit visit = new ItineraryWarningCalculator.Visit(place.getId(),
                item.getVisitDate(), item.getPlannedTime(), place.getDurationMin(), place.isAlwaysOpen(),
                place.getOpeningHoursJson());
        return new ItineraryResponse.Item(item.getId(), item.getPosition(), formatTime(item.getPlannedTime()),
                memoOf(item), toPlace(place), warningCalculator.calculate(visit, earliest.toInstant(),
                        latest == null ? null : latest.toInstant(), zone));
    }

    private static ItineraryItemResponse toResponse(ItineraryItem item) {
        return new ItineraryItemResponse(item.getId(), item.getVisitDate(), item.getPosition(),
                formatTime(item.getPlannedTime()), memoOf(item), toPlace(item.getPlace()));
    }

    private static ItineraryResponse.Place toPlace(Place place) {
        return new ItineraryResponse.Place(place.getId(), place.getNameKo(), place.getCategory(),
                place.getLat().doubleValue(), place.getLng().doubleValue(), place.getDurationMin());
    }

    /** memo 컬럼은 NULL을 허용하지만 응답은 항상 문자열이다. */
    private static String memoOf(ItineraryItem item) {
        return item.getMemo() == null ? "" : item.getMemo();
    }

    private static String formatTime(LocalTime time) {
        return time == null ? null : PLANNED_TIME.format(time);
    }

    /** 형식은 요청 DTO의 @Pattern(HH:mm)이 이미 검증했다. */
    private static LocalTime parseTime(String time) {
        return time == null ? null : LocalTime.parse(time);
    }

    private static void assignPositions(List<ItineraryItem> items, LocalDate date, Instant now) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).moveTo(date, i, now);
        }
    }

    private static void requireInTrip(Trip trip, LocalDate date) {
        if (!trip.covers(date)) {
            throw new BusinessException(ErrorCode.DATE_OUT_OF_TRIP, "날짜(" + date + ")가 여행 기간 밖입니다.");
        }
    }

    private void requireDayCapacity(int itemCount) {
        if (itemCount > properties.maxItemsPerDay()) {
            throw new BusinessException(ErrorCode.ITINERARY_DAY_FULL,
                    "한 날짜에는 일정을 최대 " + properties.maxItemsPerDay() + "개까지 넣을 수 있습니다.");
        }
    }

    private ItineraryItem findItem(long itemId, Trip trip) {
        return itemRepository.findByIdAndTrip(itemId, trip).orElseThrow(ItineraryService::itemNotFound);
    }

    private static BusinessException itemNotFound() {
        return new BusinessException(ErrorCode.ITINERARY_ITEM_NOT_FOUND, "일정 항목을 찾을 수 없습니다.");
    }
}
