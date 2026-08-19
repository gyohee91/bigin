package com.ghyinc.finance.global.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class RedissonHealthIndicatorTest {
    @InjectMocks
    private RedissonHealthIndicator redissonHealthIndicator;

    @Mock
    private RedissonClient redissonClient;

    @Test
    @DisplayName("Redisson 연결이 정상이면 UP을 반환한다")
    void health_returnsUp_whenRedissonReachable() {
        RBucket<Object> bucket = mock(RBucket.class);
        given(redissonClient.getBucket("health:check:ping")).willReturn(bucket);
        given(bucket.isExists()).willReturn(false);

        Health health = redissonHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("client", "redisson");
    }

    @Test
    @DisplayName("Redisson 연결이 끊기면 DOWN을 반환한다")
    void health_returnsDown_whenRedissonUnreachable() {
        given(redissonClient.getBucket("health:check:ping"))
                .willThrow(new RedisConnectionException("연결 실패"));

        Health health = redissonHealthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}