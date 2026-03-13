package com.ghyinc.finance.global.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CircuitBreakerStateService {
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Circuit Breaker 상태 조회
     */
    public CircuitBreaker.State getState(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        return circuitBreaker.getState();
    }

    /**
     * Circuit Breaker 상태 정보 조회
     */
    public CircuitBreakerInfo getCircuitBreakerInfo(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

        return CircuitBreakerInfo.builder()
                .name(name)
                .state(circuitBreaker.getState())
                .failureRate(metrics.getFailureRate())      //실패 호출 비율(%). failure-rate-threshold: 50 설정 시 이 값이 50% 이상이면 CLOSED → OPEN 전환
                .slowCallRate(metrics.getSlowCallRate())    //느린 호출 비율(%). slow-call-duration-threshold 초과 호출의 비율이며, slow-call-rate-threshold: 50 설정 시 50% 이상이면 OPEN 전환
                .numberOfBufferedCalls(metrics.getNumberOfBufferedCalls())      //전체 호출 수. numberOfSuccessfulCalls + numberOfFailedCalls의 합계
                .numberOfFailedCalls(metrics.getNumberOfFailedCalls())          //record-exceptions에 해당하는 예외가 발생한 호출이 카운트됨
                .numberOfSuccessfulCalls(metrics.getNumberOfSuccessfulCalls())  //성공으로 기록된 호출 수
                .numberOfNotPermittedCalls(metrics.getNumberOfNotPermittedCalls())  //OPEN 상태에서 차단된 호출 수
                .build();
    }

    /**
     * 모든 Circuit Breaker 상태 조회
     */
    public Map<String, CircuitBreakerInfo> getAllCircuitBreakers() {
        return circuitBreakerRegistry.getAllCircuitBreakers()
                .stream()
                .collect(Collectors.toMap(
                        CircuitBreaker::getName,
                        circuitBreaker -> this.getCircuitBreakerInfo(circuitBreaker.getName())
                ));
    }

    /**
     * Circuit Breaker 강제 OPEN
     */
    public void transitionToOpenState(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        circuitBreaker.transitionToOpenState();
        log.warn("Circuit Breaker 강제 OPEN: {}", name);
    }

    /**
     * Circuit Breaker 강제 CLOSE
     */
    public void transitionToCloseState(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        circuitBreaker.transitionToClosedState();
        log.info("Circuit Breaker 강제 CLOSE: {}", name);
    }

    /**
     * Circuit Breaker 강제 HALF_OPEN
     */
    public void transitionToHalfOpenState(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        circuitBreaker.transitionToHalfOpenState();
        log.warn("Circuit Breaker 강제 HALF_OPEN: {}", name);
    }

    /**
     * Circuit Breaker 메트릭 초기화
     */
    public void reset(String name) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(name);
        circuitBreaker.reset();
        log.info("Circuit Breaker 초기화: {}", name);
    }
}
