package com.ghyinc.finance.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.inquiry-recovery")
public record StaleInquiryProperties(
        @DefaultValue("2m") Duration acceptedTimeout,
        @DefaultValue("2m") Duration pendingTimeout,
        @DefaultValue("5m") Duration inProgressTimeout,
        @DefaultValue("100") int batchSize,
        @DefaultValue("30s") Duration maxRunDuration    // 한 번 실행의 시간 예상
) {}
