package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CircuitBreakerEventConfig {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @PostConstruct
    public void registerEventListeners() {
        Arrays.stream(PartnerCode.values()).forEach(partnerCode -> {
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(partnerCode.name());
            circuitBreaker.getEventPublisher()
                    .onStateTransition(event -> log.warn("[{}] Circuit Breaker 상태 변경: {} -> {}", partnerCode,
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState()
                    ))
                    .onCallNotPermitted(event -> log.warn("[{}] Circuit Breaker OPEN - 호출 차단", partnerCode))
                    .onError(event -> log.error("[{}] Circuit Breaker 오류 감지. 실패율: {}%",
                            partnerCode, circuitBreaker.getMetrics().getFailureRate()));
        });
    }

    @PostConstruct
    public void registerRetryEventListeners() {
        Arrays.stream(PartnerCode.values()).forEach(partnerCode -> {
            Retry retry = retryRegistry.retry(partnerCode.name());
            retry.getEventPublisher()
                    .onRetry(event -> log.warn("[{}] Retry 시도. 횟수={}, 원인={}", partnerCode,
                            event.getNumberOfRetryAttempts(),
                            event.getLastThrowable().getMessage()
                    ))
                    .onError(event -> log.error("[{}] Retry 모두 실패. 횟수={}",
                            partnerCode, event.getNumberOfRetryAttempts()));
        });
    }
}
