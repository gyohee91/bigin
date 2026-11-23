package com.ghyinc.finance.domain.loan.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
        StaleInquiryProperties.class,
        InquiryPollingProperties.class}
)
public class LoanPropertiesConfig {
}
