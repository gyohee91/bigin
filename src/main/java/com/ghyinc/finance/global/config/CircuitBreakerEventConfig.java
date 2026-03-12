package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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
                    .onError(event -> {
                        log.error("[{}] Circuit Breaker 오류 감지. 실패율: {}%",
                                partnerCode, event.getCircuitBreakerName());
                    });
        });
    }
}
