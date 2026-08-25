package com.ghyinc.finance.global.kafka.backoff;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

class JitteredExponentialBackOffTest {

    @Test
    @DisplayName("jitterFactor=0이면 순수 지수 백오프 값과 동일하다")
    void nextBackOff_withZeroJitterFactor_matchesDeterministicExponentialBackOff() {
        // given
        JitteredExponentialBackOff backOff = new JitteredExponentialBackOff(1000L, 2.0, 8000L, 3, 0.0);
        BackOffExecution execution = backOff.start();

        // when & then
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        assertThat(execution.nextBackOff()).isEqualTo(2000L);
        assertThat(execution.nextBackOff()).isEqualTo(4000L);
    }

    @Test
    @DisplayName("jitter가 적용되면 결정론적 지수 값의 ±jitterFactor 범위 안에서만 흔들린다")
    void nextBackOff_staysWithinJitterRange_ofDeterministicExponentialValue() {
        // given
        double jitterFactor = 0.3;
        JitteredExponentialBackOff backOff = new JitteredExponentialBackOff(1000L, 2.0, 4000L, 3, jitterFactor);
        BackOffExecution execution = backOff.start();

        // when
        long first = execution.nextBackOff();   // 결정론적 값: 1000ms
        long second = execution.nextBackOff();  // 결정론적 값: 2000ms

        // then - ±30% 범위 안에 있어야 한다
        assertThat(first).isBetween(700L, 1300L);
        assertThat(second).isBetween(1400L, 2600L);
    }

    @Test
    @DisplayName("여러 번 호출해도 음수 백오프 값은 절대 나오지 않는다")
    void nextBackOff_neverReturnsNegativeValue() {
        // given
        JitteredExponentialBackOff backOff = new JitteredExponentialBackOff(100L, 2.0, 1000L, 5, 0.9);
        BackOffExecution execution = backOff.start();

        // when & then
        for (int i = 0; i < 5; i++) {
            long next = execution.nextBackOff();
            if (next == BackOffExecution.STOP) break;
            assertThat(next).isGreaterThanOrEqualTo(0L);
        }
    }

    @Test
    @DisplayName("maxInterval을 초과한 결정론적 값에 jitter가 더해져도 maxInterval*(1+jitterFactor)를 넘지 않는다")
    void nextBackOff_respectsMaxIntervalPlusJitterUpperBound() {
        // given
        long maxInterval = 2000L;
        double jitterFactor = 0.5;
        JitteredExponentialBackOff backOff = new JitteredExponentialBackOff(1000L, 2.0, maxInterval, 5, jitterFactor);
        BackOffExecution execution = backOff.start();

        // when & then - 지수 값이 maxInterval을 넘어서도(예: 4000ms → 2000ms로 캡) 그 위에 jitter만 더해진다
        for (int i = 0; i < 4; i++) {
            long next = execution.nextBackOff();
            if (next == BackOffExecution.STOP) break;
            assertThat(next).isLessThanOrEqualTo((long) (maxInterval * (1 + jitterFactor)));
        }
    }
}
