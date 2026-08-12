package com.ghyinc.finance.global.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiterEventListener {
    private final RateLimiterRegistry rateLimiterRegistry;

    @PostConstruct
    public void registerListener() {
        rateLimiterRegistry.getAllRateLimiters()
                .forEach(this::attachListeners);

        rateLimiterRegistry.getEventPublisher()
                .onEntryAdded(event -> this.attachListeners(event.getAddedEntry()));
    }

    private void attachListeners(RateLimiter rateLimiter) {
        rateLimiter.getEventPublisher()
                .onFailure(event -> log.warn(
                        "[RateLimiter][{}] 허용량 초과로 요청 거부됨", rateLimiter.getName()));
    }
}
