package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
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
    private RateLimiterRegistry rateLimiterRegistry;

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

        // RateLimiter 초기화
        RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .limitForPeriod(5)
                .timeoutDuration(Duration.ZERO)
                .build();

        rateLimiterRegistry = RateLimiterRegistry.of(rateLimiterConfig);

        restApiClient = new RestApiClient(
                circuitBreakerRegistry,
                retryRegistry,
                rateLimiterRegistry,
                partnerRestClients
        );
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
    @DisplayName("mininumNumberOfCalls 미충족 시 CLOSE 유지")
    void post_closed_whenMininumCallsNotMet() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());

        // minimumNumberOfCalls = 4, 3건만 호출
        for(int i = 0; i <  1; i++) {
            circuitBreaker.executeSupplier(Object::new);
        }

        for(int i = 0; i < 2; i++) {
            assertThatThrownBy(() ->
                    circuitBreaker.executeSupplier(() ->{
                        throw new ExternalApiFailException("한도조회_ERROR", "API 오류");
                    })
            ).isInstanceOf(ExternalApiFailException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getMetrics().getFailureRate()).isEqualTo(-1.0f);
    }

    @Test
    @DisplayName("OPEN 상태 - CallNotPermittedException 즉시 발생")
    void post_open_throwCallNotPermittedException() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> circuitBreaker.executeSupplier(Object::new))
                .isInstanceOf(CallNotPermittedException.class);
    }

    @Test
    @DisplayName("OPEN 상태 - 차단된 호출 수 증가")
    void post_open_incrementNotPermittedCalls() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());
        circuitBreaker.transitionToOpenState();

        for(int i = 0; i < 3; i++) {
            try {
                circuitBreaker.executeSupplier(Object::new);
            } catch (CallNotPermittedException ignored) {}
        }

        assertThat(circuitBreaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(3);
    }

    @Test
    @DisplayName("OPEN → HALF_OPEN 자동 전환 - waitDuration 경과 후")
    void post_openToHalfOpen_afterWaitDuration() throws InterruptedException {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());
        circuitBreaker.transitionToOpenState();

        Thread.sleep(600);  // waitDurationInOpenState(500ms) 경과

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    @DisplayName("HALF_OPEN → CLOSED 전환 - 테스트 호출 성공 시 복구")
    void post_halfOpenToClosed_whenTestCallsSucceed() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());

        // CLOSE → OPEN → HALF_OPEN 순서로 전환
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        // permittedNumberOfCallsInHalfOpenState(2) 모두 성공
        for(int i = 0; i < 2; i++) {
            circuitBreaker.executeSupplier(Object::new);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN → OPEN 재전환 - 테스트 호출 실패 시")
    void post_halfOpenToOpen_whenTestCallsFail() {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());

        // CLOSE → OPEN → HALF_OPEN 순서로 전환
        circuitBreaker.transitionToOpenState();
        circuitBreaker.transitionToHalfOpenState();

        for(int i = 0; i < 2; i++) {
            assertThatThrownBy(() ->
                    circuitBreaker.executeSupplier(() -> {
                        throw new ExternalApiFailException("한도조회_ERROR", "API 오류");
                    })
            ).isInstanceOf(ExternalApiFailException.class);
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("금융사별 Circuit Breaker 독립 - TOSS_BANK OPEN이 KAKAO_BANk 미영향")
    void post_circuitBreakerIndependentPerPartner() {
        CircuitBreaker kakao = circuitBreakerRegistry.circuitBreaker(PartnerCode.KAKAO_BANK.name());
        CircuitBreaker toss = circuitBreakerRegistry.circuitBreaker(PartnerCode.TOSS_BANK.name());

        toss.transitionToOpenState();

        assertThat(kakao.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(toss.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("RateLimiter - 허용량 초과 시 RequestNotPermitted 즉시 발생")
    void post_rateLimiter_requestNotPermittedWhenLimitExceeded() {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(PartnerCode.KAKAO_BANK.name());

        // limitForPeriod(5) 소진
        for(int i = 0; i < 5; i++) {
            rateLimiter.executeSupplier(Object::new);
        }

        // 6번째 요청 → 허용량 초과 → RequestNotPermitted 즉시 발생
        assertThatThrownBy(() -> rateLimiter.executeSupplier(Object::new))
                .isInstanceOf(RequestNotPermitted.class);
    }

    @Test
    @DisplayName("RateLimiter - limitRefreshPeriod 경과 후 허용량 초기화")
    void post_rateLimit_permitRestoredAfterRefreshPeriod() throws InterruptedException {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(PartnerCode.KAKAO_BANK.name());

        // limitForPeriod(5) 소진
        for(int i = 0; i < 5; i++) {
            rateLimiter.executeSupplier(Object::new);
        }

        // limitRefreshPeriod(1s) 경과 후 허용량 초기화
        Thread.sleep(1100);

        // 초기화 후 정상 호출 가능
        rateLimiter.executeSupplier(Object::new);

        assertThat(rateLimiter.getMetrics().getAvailablePermissions()).isEqualTo(4);
    }

    @Test
    @DisplayName("RateLimiter - 금융사별 독립 - KAKAO_BANK 한도 소진이 TOSS_BANK 미영향")
    void post_rateLimiter_independentPerPartner() {
        RateLimiter kakaoRateLimiter = rateLimiterRegistry.rateLimiter(PartnerCode.KAKAO_BANK.name());
        RateLimiter tossRateLimiter = rateLimiterRegistry.rateLimiter(PartnerCode.TOSS_BANK.name());

        // KAKAO_BANK 허용량 전부 소진
        for(int i = 0; i < 5; i++) {
            kakaoRateLimiter.executeSupplier(Object::new);
        }

        // KAKAO_BANK → RequestNotPermitted
        assertThatThrownBy(() -> kakaoRateLimiter.executeSupplier(Object::new))
                .isInstanceOf(RequestNotPermitted.class);

        // TOSS_BANK → 정상 호출 가능 (독립적)
        tossRateLimiter.executeSupplier(Object::new);
        assertThat(tossRateLimiter.getMetrics().getAvailablePermissions()).isEqualTo(4);
    }
}