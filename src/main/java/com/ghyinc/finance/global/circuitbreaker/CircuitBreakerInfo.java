package com.ghyinc.finance.global.circuitbreaker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CircuitBreakerInfo {
    private String name;
    private CircuitBreaker.State state;
    private float failureRate;
    private float slowCallRate;
    private int numberOfBufferedCalls;
    private int numberOfFailedCalls;
    private int numberOfSuccessfulCalls;
    private int numberOfSlowCalls;
    private long numberOfNotPermittedCalls;
}
