package com.ghyinc.finance.domain.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

@ConfigurationProperties(prefix = "app.inquiry-recovery")
public record StaleInquiryProperties(
        @DefaultValue("2m") Duration acceptedTimeout,
        @DefaultValue("2m") Duration pendingTimeout,
        @DefaultValue("5m") Duration inProgressTimeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("30s") Duration maxRunDuration,    // 한 번 실행의 시간 예산
        @DefaultValue("10s") Duration fixedDelay
) {
    public Duration minTimeout() {
        return Collections.min(List.of(acceptedTimeout, pendingTimeout, inProgressTimeout));
    }
}
