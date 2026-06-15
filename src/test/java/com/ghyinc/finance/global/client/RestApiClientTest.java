package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class RestApiClientTest {
    private RestApiClient restApiClient;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RetryRegistry retryRegistry;

    @Mock
    private Map<PartnerCode, RestClient> partnerRestClients;

    @BeforeEach
    void setUp() {
        // Circuit Breaker 초기화
        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(500))
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .recordExceptions(ExternalApiFailException.class)
                .build();
        circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig);

        // Retry 초기화
        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(100))
                .retryExceptions(ExternalApiFailException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build();
        retryRegistry = RetryRegistry.of(retryConfig);

        restApiClient = new RestApiClient(circuitBreakerRegistry, retryRegistry, partnerRestClients);
    }

    @Test
    @DisplayName("CLOSED -> OPEN 전환 - 실패율 50% 이상 시")
    void post_closedToOpen_whenFailureRateExceedsThreshold() {

        CircuitBreaker circuitBreaker = circuitBreakerRegistry
                .circuitBreaker(PartnerCode.TOSS_BANK.name());

        // 성공 2건
        for(int i = 0; i < 2; i++) {
            circuitBreaker.executeSupplier(Object::new);
        }

        // 실패 2건
        for(int i = 0; i < 2; i++) {
            assertThatThrownBy(() ->
                    circuitBreaker.executeSupplier(() ->{
                        throw new ExternalApiFailException("한도조회_ERROR", "API 오류");
                    })
            ).isInstanceOf(ExternalApiFailException.class);
        }

        // then
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker.getMetrics().getFailureRate()).isEqualTo(50.0f);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
        assertThat(circuitBreaker.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(2);
    }

    @Test
    void post() {
    }
}