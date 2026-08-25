package com.ghyinc.finance.global.kafka.backoff;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.concurrent.ThreadLocalRandom;

public class JitteredExponentialBackOff implements BackOff {
    private final ExponentialBackOff delegate;
    private final double jitterFactor;

    public JitteredExponentialBackOff(long initialInterval, double multiplier,
                                      long maxInterval, long maxElapsedTime, double jitterFactor) {
        this.delegate = new ExponentialBackOff(initialInterval, multiplier);
        this.delegate.setMaxInterval(maxInterval);
        this.delegate.setMaxElapsedTime(maxElapsedTime);
        this.jitterFactor = jitterFactor;
    }

    @Override
    public BackOffExecution start() {
        BackOffExecution execution = delegate.start();
        return () -> {
            long next = execution.nextBackOff();
            if (next == BackOffExecution.STOP)
                return BackOffExecution.STOP;
            long jitter = (long) (next * jitterFactor * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
            return Math.max(0, next + jitter);
        };
    }
}
