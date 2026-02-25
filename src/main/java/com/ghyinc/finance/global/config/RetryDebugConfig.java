package com.ghyinc.finance.global.config;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RetryDebugConfig {
    private final RetryRegistry retryRegistry;

    @PostConstruct
    public void checkRetry() {
        log.warn("╔════════════════════════════════════════════════════════════");
        log.warn("║ Retry 설정 진단");
        log.warn("╚════════════════════════════════════════════════════════════");

        int count = 0;
        for(Retry retry : retryRegistry.getAllRetries()) {
            count++;
            log.warn("Retry[{}] 이름: {}", count, retry.getName());
            log.warn("   - maxAttempts: {}", retry.getRetryConfig().getMaxAttempts());

            retry.getEventPublisher().onRetry(event -> {
                log.warn("RETRY EVENT DETECTED! attempts={}", event.getNumberOfRetryAttempts());
            });
        }

        if (count == 0) {
            log.error("CRITICAL: Retry 인스턴스가 0개입니다!");
            log.error("application.yml 확인 필요!");
        } else {
            log.warn("총 {}개 Retry 인스턴스 등록됨", count);
        }
    }
}
