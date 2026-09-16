package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class PartnerOrTimeoutConfig {
    private final PartnerApiProperties partnerApiProperties;
    private final RetryRegistry retryRegistry;
    private final RetryBackoffProperties retryBackoffProperties;

    private static final long MARGIN_MS = 1000;

    @Bean
    public Map<PartnerCode, Duration> partnerOrTimeout() {
        return partnerApiProperties.getPartners().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> {
                            PartnerCode partnerCode = entry.getKey();
                            PartnerApiProperties.PartnerApiConfig config = entry.getValue();

                            RetryConfig retryConfig = retryRegistry.retry(partnerCode.name()).getRetryConfig();
                            int maxAttempts = retryConfig.getMaxAttempts();

                            long worstCasePerAttempt = PartnerConnectionPoolConfig.CONNECTION_REQUEST_TIMEOUT_MS
                                    + config.getConnectTimeoutMs()
                                    + config.getReadTimeoutMs();

                            long worstCaseBackoffMs = this.calculateWorstCaseBackoff(maxAttempts);

                            long orTimeoutMs = maxAttempts * worstCasePerAttempt + worstCaseBackoffMs + MARGIN_MS;

                            return Duration.ofMillis(orTimeoutMs);
                        }
                ));
    }

    /**
     * attempt 사이 백오프(maxAttempts-1회)의 최대값 총합을 직접 계산한다
     */
    private long calculateWorstCaseBackoff(int maxAttempts) {
        long waitMs = retryBackoffProperties.getWaitDuration().toMillis();
        double multiplier = retryBackoffProperties.getExponentialBackoffMultiplier();
        long maxWaitMs = retryBackoffProperties.getMaxWaitDuration().toMillis();
        double jitter = retryBackoffProperties.getRandomizedWaitFactor();

        long total = 0;
        long currentWait = waitMs;
        for (int attempt = 1; attempt  < maxAttempts; attempt++) {
            long cappedWait = Math.max(currentWait, maxWaitMs);
            total += (long) (cappedWait * (1 + jitter));
            currentWait = (long) (currentWait * multiplier);
        }

        return total;
    }
}
