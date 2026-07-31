package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractNotificationSenderTest {
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private RetryRegistry retryRegistry;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        retryRegistry = RetryRegistry.of(
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ZERO)
                        .build()
        );
    }

    private Notification notification() {
        return Notification.builder()
                .channelType(ChannelType.SMS)
                .recipient("010-1234-5678")
                .build();
    }

    @Test
    @DisplayName("정상 응답이면 그대로 반환한다")
    void returnsResponseAsIsWhenSuccessful() {
        ExternalApiResponse success = ExternalApiResponse.success("SUCCESS", "ok");
        TestSender sender = new TestSender(circuitBreakerRegistry, retryRegistry, notification -> success);

        ExternalApiResponse result = sender.send(this.notification());

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("실패가 반복되면 재시도 후 UNAVAILABLE로 fallback한다")
    void fallbackToUnavailableAfterRetriesExhausted() {
        AtomicInteger callCount = new AtomicInteger();
        TestSender sender = new TestSender(circuitBreakerRegistry, retryRegistry, notification -> {
            callCount.incrementAndGet();
            return ExternalApiResponse.fail("FAIL", "실패");
        });

        ExternalApiResponse result = sender.send(this.notification());

        assertThat(result.getResultCode()).isEqualTo("UNAVAILABLE");
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("CircuitBreaker가 OPEN 상태면 API 호출 없이 즉시 fallback한다")
    void skipApiCallWhenCircuitBreakerIsOpen() {
        AtomicInteger callCount = new AtomicInteger();
        TestSender sender = new TestSender(circuitBreakerRegistry, retryRegistry, notification -> {
            callCount.incrementAndGet();
            return ExternalApiResponse.success("SUCCESS", "ok");
        });

        circuitBreakerRegistry.circuitBreaker(ChannelType.SMS.name()).transitionToOpenState();

        ExternalApiResponse result = sender.send(this.notification());

        assertThat(result.getResultCode()).isEqualTo("UNAVAILABLE");
        assertThat(callCount.get()).isZero();
    }

    private static class TestSender extends AbstractNotificationSender {
        private final Function<Notification, ExternalApiResponse> behavior;

        public TestSender(CircuitBreakerRegistry cbRegistry, RetryRegistry retryRegistry, Function<Notification, ExternalApiResponse> behavior) {
            super(cbRegistry, retryRegistry, RestClient.create());  // post()를 안 쓰므로 최소 인스턴스만 필요
            this.behavior = behavior;
        }

        @Override
        protected ExternalApiResponse callApi(Notification notification) {
            return behavior.apply(notification);
        }

        @Override
        public ChannelType getChannelType() {
            return ChannelType.SMS;
        }
    }
}