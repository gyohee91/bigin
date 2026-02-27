package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.SmsRequest;
import com.ghyinc.finance.domain.notification.dto.SmsResponse;
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

    private final RestTemplate restTemplate;

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
            SmsRequest requestDto = SmsRequest.builder()
                    .recipient(notification.getRecipient())
                    .title(notification.getTitle())
                    .content(notification.getContent())
                    .build();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<SmsRequest> httpEntity = new HttpEntity<>(requestDto, headers);

            ResponseEntity<SmsResponse> responseEntity = restTemplate.exchange(
                    "http://localhost:8090/send/sms",
                    HttpMethod.POST,
                    httpEntity,
                    SmsResponse.class
            );

            SmsResponse response = responseEntity.getBody();

            if("SUCCESS".equals(response.getResultCode())) {
                return ExternalApiResponse.success(notification.getId(), response.getResultCode(), response);
            }
            else {
                return ExternalApiResponse.fail(notification.getId(), response.getResultCode(), "error");
                //throw new ExternalApiFailException(response.getResultCode(), "error");
            }

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
