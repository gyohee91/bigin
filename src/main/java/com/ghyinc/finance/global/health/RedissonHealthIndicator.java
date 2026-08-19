package com.ghyinc.finance.global.health;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RedissonHealthIndicator implements HealthIndicator {
    private final RedissonClient redissonClient;

    private static final String PING_KEY = "health:check:ping";

    @Override
    public Health health() {
        try {
            // 부작용 없는 EXISTS 호출로 왕복만 확인 (PING 전용 API가 없어 대체)
            redissonClient.getBucket(PING_KEY).isExists();
            return Health.up()
                    .withDetail("client", "redisson")
                    .build();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("client", "redisson")
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
