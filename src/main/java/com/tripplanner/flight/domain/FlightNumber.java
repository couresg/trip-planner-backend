package com.tripplanner.flight.domain;

import com.tripplanner.common.exception.BusinessException;
import com.tripplanner.common.exception.ErrorCode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 정규화된 편명. 공백·하이픈 제거 + 대문자 변환 후 {@code ^[A-Z0-9]{2}\d{1,4}$}로 검증한다 ("ke 723" → "KE723").
 * 숫자부의 앞자리 0은 제거한다 ("KE0723" → "KE723"). 같은 편이 캐시 키를 하나만 갖게 하기 위해서다.
 */
public record FlightNumber(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{2}\\d{1,4}$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s-]+");
    private static final String FORMAT_MESSAGE = "편명은 항공사 코드 2자리 + 숫자 1~4자리입니다. 예: KE723";

    public FlightNumber {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_FLIGHT_NO, FORMAT_MESSAGE);
        }
    }

    public static FlightNumber of(String raw) {
        if (raw == null) {
            throw new BusinessException(ErrorCode.INVALID_FLIGHT_NO, FORMAT_MESSAGE);
        }
        String compact = SEPARATORS.matcher(raw.strip()).replaceAll("").toUpperCase(Locale.ROOT);
        if (FORMAT.matcher(compact).matches()) {
            compact = compact.substring(0, 2) + stripLeadingZeros(compact.substring(2));
        }
        return new FlightNumber(compact);
    }

    private static String stripLeadingZeros(String digits) {
        String stripped = digits.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }

    @Override
    public String toString() {
        return value;
    }
}
