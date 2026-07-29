package com.ghyinc.finance.global.config;

import io.github.resilience4j.retry.Retry;
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
        // 기동 시점에 이미 설정되어 있는 인스턴스(default/KAKAO_BANK/SMS 등) 전체에 리스너 부착
        retryRegistry.getAllRetries().forEach(this::attachListeners);

        // 이후 런타임에 새로 생성되는 인스턴스에도 자동으로 리스너 부착
        retryRegistry.getEventPublisher()
                .onEntryAdded(event -> this.attachListeners(event.getAddedEntry()));
    }

    private void attachListeners(Retry retry) {
        retry.getEventPublisher()
                .onRetry(event -> log.info("[Retry][{}] 재시도 발생 - 시도 횟수{}/{}, 예외: {}",
                        retry.getName(),
                        event.getNumberOfRetryAttempts(),
                        retry.getRetryConfig().getMaxAttempts(),
                        event.getLastThrowable().getMessage()
                ))
                .onError(event -> log.warn("[Retry][{}] 재시도 소진 - 최종 실패. 예외: {}",
                        retry.getName(), event.getLastThrowable().getMessage()))
                .onSuccess(event -> log.debug("[Retry][{}] 성공 - 총 시도 횟수: {}",
                        retry.getName(), event.getNumberOfRetryAttempts()));
    }
}
