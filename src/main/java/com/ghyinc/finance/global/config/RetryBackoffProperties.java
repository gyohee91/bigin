package com.ghyinc.finance.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * resilience4j.retry.configs.default 값을 그대로 읽어와 orTimeout 계산에 재사용한다.
 * RetryConfig의 IntervalFunction은 jitter가 섞여 있어 "최악의 경우" 값을 결정적으로
 * 조회할 방법이 없어서, 동일한 yaml 키를 별도 바인딩해 최악의 경우 백오프를 직접 계산한다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "resilience4j.retry.configs.default")
public class RetryBackoffProperties {
    private Duration waitDuration;
    private double exponentialBackoffMultiplier;
    private Duration maxWaitDuration;
    private double randomizedWaitFactor;
}
