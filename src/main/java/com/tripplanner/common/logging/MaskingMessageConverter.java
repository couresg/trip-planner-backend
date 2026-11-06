package com.tripplanner.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** logback-spring.xml에서 %m/%msg/%message를 대체한다. */
public class MaskingMessageConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.mask(super.convert(event));
    }
}
