package com.tripplanner.itinerary.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;

public record ReorderItineraryDayRequest(
        @NotNull(message = "필수 값입니다.")
        @Size(max = 200, message = "항목은 한 번에 최대 200개까지 보낼 수 있습니다.")
        List<@NotNull(message = "항목 ID는 null일 수 없습니다.") Long> itemIds) {

    @JsonIgnore
    @AssertTrue(message = "항목 ID가 중복되었습니다.")
    public boolean isItemIdsDistinct() {
        return itemIds == null || new HashSet<>(itemIds).size() == itemIds.size();
    }
}
