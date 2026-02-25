package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.SmsRequest;
import com.ghyinc.finance.domain.notification.dto.SmsResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSenderService {
    private final RestTemplate restTemplate;
    private final RetryRegistry retryRegistry;

    @PostConstruct
    public void checkRetryConfig() {
        log.error("╔════════════════════════════════════════════════════════════");
        log.error("║ Retry 설정 확인");

        io.github.resilience4j.retry.Retry retry = retryRegistry.find("notificationApi").orElse(null);

        if (retry == null) {
            log.error("║ ❌❌❌ 'notificationApi' Retry를 찾을 수 없음!");
        } else {
            log.error("║ ✅ Retry 발견: {}", retry.getName());
            log.error("║    - maxAttempts: {}", retry.getRetryConfig().getMaxAttempts());

            // 테스트 이벤트 리스너 등록
            retry.getEventPublisher().onRetry(event -> {
                log.error("🔥🔥🔥 RETRY EVENT! attempts={}", event.getNumberOfRetryAttempts());
            });
        }

        log.error("╚════════════════════════════════════════════════════════════");
    }

    @Retry(name = "notificationApi")
    @CircuitBreaker(name = "notificationApi", fallbackMethod = "sendCircuitBreakerFallback")
    public void send(Notification notification) {
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
    }

    //Circuit Breaker OPEN 시 (장애 감지)
    private void sendCircuitBreakerFallback(Notification notification, Exception e) {
        log.error("Circuit Breaker OPEN - 발송 실패 - recipient={}", notification.getRecipient(), e);

    }

    private void sendRetryFallback(Notification notification, Exception e) {
        log.error("Retry 시도 - recipient={}", notification.getRecipient(), e);

    }
}
