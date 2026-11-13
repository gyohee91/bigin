package com.ghyinc.finance.global.filter;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yaml 의 서킷 브레이커 설정이 인바운드 Rate Limiter 의 Redis 장애를 실제로 "실패"로 집계하는지 확인한다.
 *
 * <p>공용 default 설정은 파트너 HTTP 예외만 record-exceptions 로 지정하고 있어, 별도 설정 없이
 * 인스턴스를 만들면 Redisson 예외가 실패로 집계되지 않아 서킷이 열리지 않는다.</p>
 */
@SpringBootTest
class InboundRateLimiterCircuitBreakerConfigTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    @DisplayName("inboundRateLimiterRedis 서킷은 Redisson 타임아웃 예외를 실패로 집계한다")
    void records_redisTimeoutException() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("inboundRateLimiterRedis");

        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate()
                .test(new RedisTimeoutException("timeout"))).isTrue();
    }

    @Test
    @DisplayName("inboundRateLimiterRedis 서킷은 Redisson 연결 예외를 실패로 집계한다")
    void records_redisConnectionException() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("inboundRateLimiterRedis");

        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate()
                .test(new RedisConnectionException("connection"))).isTrue();
    }
}
