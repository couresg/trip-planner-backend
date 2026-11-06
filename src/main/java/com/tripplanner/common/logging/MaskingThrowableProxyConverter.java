package com.tripplanner.common.logging;

import ch.qos.logback.classic.spi.IThrowableProxy;
import org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter;

/** logback-spring.xml에서 Spring Boot 기본 예외 출력(%wEx)을 대체한다. 예외 메시지·원인 체인의 비밀값을 가린다. */
public class MaskingThrowableProxyConverter extends ExtendedWhitespaceThrowableProxyConverter {

    @Override
    protected String throwableProxyToString(IThrowableProxy tp) {
        return LogMasker.mask(super.throwableProxyToString(tp));
    }
}
