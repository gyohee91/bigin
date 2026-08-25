package com.ghyinc.finance.global.kafka.backoff;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 지수 백오프 + jitter를 적용한 {@link BackOff}.
 *
 * <p>{@link org.springframework.util.backoff.ExponentialBackOff}에 위임하지 않는다 - 그 클래스의
 * {@code maxElapsedTime}은 "시작 이후 실제 경과한 wall-clock 시간(ms)"이 이 값을 넘으면 STOP을
 * 반환하는 것이지 "몇 번째 시도인지"를 세는 게 아니다. 재시도 "횟수"를 제한하려는 의도로 여기에 작은
 * 정수(예: 3)를 넘기면, 그 값이 "3ms 안에 재시도 안 끝나면 즉시 STOP"으로 해석되어 사실상 첫 호출부터
 * (또는 두 번째 호출부터) 바로 STOP이 나가는 버그가 된다. 그래서 attempt 횟수를 직접 카운트한다.
 */
public class JitteredExponentialBackOff implements BackOff {
    private final long initialInterval;
    private final double multiplier;
    private final long maxInterval;
    private final long maxAttempts;
    private final double jitterFactor;

    public JitteredExponentialBackOff(long initialInterval, double multiplier,
                                       long maxInterval, long maxAttempts, double jitterFactor) {
        this.initialInterval = initialInterval;
        this.multiplier = multiplier;
        this.maxInterval = maxInterval;
        this.maxAttempts = maxAttempts;
        this.jitterFactor = jitterFactor;
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private long attempt = 0;

            @Override
            public long nextBackOff() {
                attempt++;
                if (attempt > maxAttempts) {
                    return STOP;
                }

                long deterministic = (long) (initialInterval * Math.pow(multiplier, attempt - 1));
                deterministic = Math.min(deterministic, maxInterval);

                long jitter = (long) (deterministic * jitterFactor * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
                return Math.max(0, deterministic + jitter);
            }
        };
    }
}
