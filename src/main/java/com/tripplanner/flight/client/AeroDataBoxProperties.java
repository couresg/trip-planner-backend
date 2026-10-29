package com.tripplanner.flight.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AeroDataBox(RapidAPI) 설정. apiKey는 환경변수 AERODATABOX_API_KEY로만 주입한다. */
@ConfigurationProperties(prefix = "external.aerodatabox")
public record AeroDataBoxProperties(
        String apiKey,
        String apiHost,
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout) {

    public AeroDataBoxProperties {
        apiKey = apiKey == null ? "" : apiKey;
        apiHost = apiHost == null || apiHost.isBlank() ? "aerodatabox.p.rapidapi.com" : apiHost;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://aerodatabox.p.rapidapi.com" : baseUrl;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
    }

    /** API 키가 로그에 남지 않도록 가린다. */
    @Override
    public String toString() {
        return "AeroDataBoxProperties[apiHost=" + apiHost + ", baseUrl=" + baseUrl + ", connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + ", apiKey=" + (apiKey.isEmpty() ? "" : "***") + "]";
    }
}
