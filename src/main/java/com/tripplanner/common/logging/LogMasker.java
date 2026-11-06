package com.tripplanner.common.logging;

import java.util.regex.Pattern;

/**
 * 로그 문자열에서 비밀값(소유 토큰, API 키, 비밀번호, 인증 헤더)을 ****로 가린다. 소스에서 남기지 않는 것이 원칙이고 이것은 안전망이다.
 * 이름 뒤의 값만 가린다. 구분자는 =, :, : [ (Spring 요청 파라미터 로그 key:[v]) 를 받고 값 앞뒤 따옴표를 허용한다.
 * 이름 없는 UUID는 여행 ID와 구분할 수 없어 가리지 않는다.
 */
public final class LogMasker {

    static final String MASK = "****";

    /** 이름과 값 사이: 따옴표, 공백, = 또는 :, 여는 대괄호, 따옴표. */
    private static final String SEPARATOR = "[\"']?\\s*[:=]\\s*\\[?[\"']?";
    /** 소유 토큰은 콤마로 여러 개를 보내므로(GET /trips) 콤마도 값에 포함한다. */
    private static final Pattern OWNER_TOKEN = Pattern.compile(
            "(?i)((?:x-owner-token|owner[_-]?token)" + SEPARATOR + ")[^\\s\"';&}\\]]+");
    /** Authorization: Bearer x / Basic x / x */
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)((?<![\\w-])authorization" + SEPARATOR + "(?:(?:bearer|basic)\\s+)?)[^\\s\"',;&}\\]]+");
    /** API 키·비밀번호·토큰. key·token은 단어 전체만(monkey=, x-owner-token은 제외). */
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:x-rapidapi-key|api[_-]?key|password|passwd|secret|(?<![\\w-])token|(?<![\\w-])key)"
                    + SEPARATOR + ")[^\\s\"',;&}\\]]+");

    private LogMasker() {
    }

    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = OWNER_TOKEN.matcher(text).replaceAll("$1" + MASK);
        masked = AUTHORIZATION.matcher(masked).replaceAll("$1" + MASK);
        return NAMED_SECRET.matcher(masked).replaceAll("$1" + MASK);
    }
}
