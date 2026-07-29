package com.ghyinc.finance.global.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CircuitBreakerEventListener {
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @PostConstruct
    public void registerListeners() {
        // 기동 시점에 이미 설정된 인스턴스(KAKAO_BANK/SMS 등) 전체에 리스너 부착
        circuitBreakerRegistry.getAllCircuitBreakers()
                .forEach(this::attachListeners);

        // 이후 런타임에 새로 생성되는 인스턴스에도 자동으로 리스너 부착
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> this.attachListeners(event.getAddedEntry()));
    }

    private void attachListeners(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("[CircuitBreaker][{}] 상태 전이: {} -> {}",
                        circuitBreaker.getName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()))
                .onCallNotPermitted(event -> log.warn(
                        "[CircuitBreaker][{}] 호출 차단됨 (OPEN 상태) - fallback으로 즉시 처리", circuitBreaker.getName()));
    }
}
