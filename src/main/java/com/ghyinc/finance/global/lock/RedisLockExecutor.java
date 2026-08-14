package com.ghyinc.finance.global.lock;

import com.ghyinc.finance.global.exception.LockUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLockExecutor {
    private final RedissonClient redissonClient;

    public <T> T execute(String lockKey, long waitSeconds, long leaseSeconds,
                         Supplier<T> action, Supplier<T> onLockUnavailable) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitSeconds, leaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return onLockUnavailable.get();
        } catch (RedisConnectionException | RedisTimeoutException e) {
            log.error("[분산 락] Redis 연결 실패. lockKey={}", lockKey, e);
            throw new LockUnavailableException("일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.", e);
        }

        if (!acquired) {
            return onLockUnavailable.get();
        }

        try {
            return action.get();
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                // leaseTime 경과 시 자동 해제되므로 여기서 실패해도 영구 데드락은 아님
                log.warn("[분산 락] 락 해제 실패. lockKey={}", lockKey, e);
            }
        }
    }

    public void execute(String lockKey, long waitSeconds, long leaseSeconds,
                        Runnable action, Runnable onLockUnavailable) {
        this.<Void>execute(lockKey, waitSeconds, leaseSeconds,
                () -> {
                    action.run();
                    return null;
                },
                () -> {
                    onLockUnavailable.run();
                    return null;
                });
    }
}
