package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.SmsRequest;
import com.ghyinc.finance.domain.notification.dto.SmsResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.global.client.ExternalApiClient;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSenderService {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;
    private final ExternalApiClient apiClient;

    @Value("${notification.sender.base-url}")
    private String url;

    public ExternalApiResponse call(Notification notification) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("default");
        Retry retry = retryRegistry.retry("default");

        return this.execute(notification, circuitBreaker, retry);
    }

    private ExternalApiResponse execute(
            Notification notification,
            CircuitBreaker circuitBreaker,
            Retry retry
    ) {
        Supplier<ExternalApiResponse> apiCall = () -> {
            //ExternalApiResponse response = apiClient.requestRestTemplate(notification, url);
            ExternalApiResponse response = apiClient.requestWebClient(notification, url);

            if(!response.isSuccess()) {
                throw new ExternalApiFailException(response.getResultCode(), "외부 API 실패 - CODE: " + response.getResultCode());
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
        if(ex instanceof CallNotPermittedException) {
            log.warn("[{}] Circuit Breaker OPEN - 요청 차단됨", notification.getId());
        }
        else if(ex instanceof ExternalApiFailException apiEx) {
            log.warn("[{}] 재시도 소진 - body 실패 응답. resultCode: {}",
                    notification.getId(), apiEx.getResultCode());
        }
        else {
            log.warn("[{}] 재시도 소진 - 네트워크/타임아웃. error: {}",
                    notification.getId(), ex.getMessage());
        }

        return ExternalApiResponse.unavailable(notification.getId());
    }
}
