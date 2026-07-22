package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.function.Supplier;

/**
 * 채널별 CircuitBreaker/Retry 실행 로직 공통화
 * 채널 구현체(SMS/EMAIL/KAKAOTALK)는 callApi()만 구현하면 됨.
 * CB/Retry 인스턴스명은 ChannelType.name()과 1:1 매핑 (application.yaml resilience4j.instances 참고)
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractNotificationSender implements NotificationSender {
    private static final String REQUEST_ID_KEY = "requestId";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    protected abstract ExternalApiResponse callApi(Notification notification);

    @Override
    public ExternalApiResponse send(Notification notification) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(this.getChannelType().name());
        Retry retry = retryRegistry.retry(this.getChannelType().name());

        Supplier<ExternalApiResponse> apiCall = () -> {
            ExternalApiResponse response = this.callApi(notification);

            if(!response.isSuccess()) {
                throw new ExternalApiFailException(
                        response.getResultCode(),
                        "외부 API 실패 - CODE: " + response.getResultCode()
                );
            }

            return response;
        };

        return Decorators.ofSupplier(apiCall)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .withFallback(ex -> this.fallback(notification, ex))
                .decorate()
                .get();
    }

    private ExternalApiResponse fallback(Notification notification, Throwable ex) {
        if (ex instanceof CallNotPermittedException) {
            log.warn("[{}][{}]Circuit Breaker OPEN - 요청 차단됨", this.getChannelType(), notification.getId());
        } else if (ex instanceof ExternalApiFailException apiEx) {
            log.warn("[{}][{}] 재시도 소진 - body 실패 응답. resultCode: {}",
                    this.getChannelType(), notification.getId(), apiEx.getMessage());
        } else {
            log.warn("[{}][{}] 재시도 소진 - 네트워크/타임아웃. error: {}",
                    this.getChannelType(), notification.getId(), ex.getMessage());
        }

        return ExternalApiResponse.unavailable(MDC.get(REQUEST_ID_KEY));
    }
}
