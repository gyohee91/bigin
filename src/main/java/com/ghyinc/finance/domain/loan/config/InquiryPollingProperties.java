package com.ghyinc.finance.domain.loan.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.inquiry-polling")
public record InquiryPollingProperties(
        @DefaultValue("60s") Duration clientWaitLimit,      // FE가 기다릴 최대 시간 (예시값)
        @DefaultValue("2s") Duration pollInterval           // FE 폴링 간격 안내값
) {
}
