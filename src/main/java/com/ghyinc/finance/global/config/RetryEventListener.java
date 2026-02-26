package com.ghyinc.finance.global.config;

import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RetryEventListener {
    private final RetryRegistry retryRegistry;

    @PostConstruct
    public void registerListeners() {
        retryRegistry.retry("notificationApi")
                .getEventPublisher()
                .onRetry(event -> {
                    log.info("[Retry] 재시도 발생 - 시도 횟수{}/{}, 예외: {}",
                            event.getNumberOfRetryAttempts(),
                            3,
                            event.getLastThrowable().getMessage()
                    );
                })
                .onError(event -> {
                    log.warn("[Retry] 재시도 소진 - 최종 실패. 예외: {}",
                            event.getLastThrowable().getMessage());
                })
                .onSuccess(event -> {
                    log.debug("[Retry] 성공 - 총 시도 횟수: {}",
                            event.getNumberOfRetryAttempts());
                });
    }
}
