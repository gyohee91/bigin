package com.ghyinc.finance.global.lock;

import com.ghyinc.finance.global.exception.LockUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * 3개 서비스(ProductService, LoanLimitService, LoanLimitResultService)에서 공통으로 쓰던
 * Redisson 락 획득/해제 보일러플레이트를 추출한 DistributedLockExecutor의 락 매커니즘 자체를 검증한다.
 * 인터럽트/Redis 연결 실패 처리는 여기서만 검증하고, 각 서비스 테스트는 action/fallback의
 * 비즈니스 로직만 검증하면 된다.
 */
@ExtendWith(MockitoExtension.class)
class RedisLockExecutorTest {

    @InjectMocks
    private RedisLockExecutor lockExecutor;

    @Mock
    private RedissonClient redissonClient;

    @Test
    @DisplayName("락 획득 성공 시 action을 실행하고 결과를 반환한다")
    void execute_runsAction_whenLockAcquired() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);

        String result = lockExecutor.execute("key", 3, 5,
                () -> "action-result",
                () -> "fallback-result");

        assertThat(result).isEqualTo("action-result");
        then(rLock).should().unlock();
    }

    @Test
    @DisplayName("락 획득 실패 시 onLockUnavailable을 실행하고 unlock은 호출하지 않는다")
    void execute_runsFallback_whenLockNotAcquired() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willReturn(false);

        String result = lockExecutor.execute("key", 3, 5,
                () -> "action-result",
                () -> "fallback-result");

        assertThat(result).isEqualTo("fallback-result");
        then(rLock).should(never()).unlock();
    }

    @Test
    @DisplayName("락 대기 중 인터럽트 발생 시 onLockUnavailable을 실행하고 인터럽트 플래그를 복원한다")
    void execute_runsFallback_whenInterrupted() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willThrow(new InterruptedException());

        String result = lockExecutor.execute("key", 3, 5,
                () -> "action-result",
                () -> "fallback-result");

        assertThat(result).isEqualTo("fallback-result");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 다음 테스트로 인터럽트 상태가 전파되지 않도록 정리
    }

    @Test
    @DisplayName("Redis 연결 실패 시 LockUnavailableException으로 변환되어 전파된다")
    void execute_throwsLockUnavailableException_whenRedisConnectionFails() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willThrow(new RedisConnectionException("연결 실패"));

        assertThatThrownBy(() -> lockExecutor.execute("key", 3, 5,
                () -> "action-result",
                () -> "fallback-result"))
                .isInstanceOf(LockUnavailableException.class);

        then(rLock).should(never()).unlock();
    }

    @Test
    @DisplayName("action 실행 중 예외가 발생해도 락은 해제된다")
    void execute_unlocksLock_whenActionThrows() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);

        assertThatThrownBy(() -> lockExecutor.execute("key", 3, 5,
                () -> { throw new IllegalStateException("action 실패"); },
                () -> "fallback-result"))
                .isInstanceOf(IllegalStateException.class);

        then(rLock).should().unlock();
    }

    @Test
    @DisplayName("void(Runnable) 오버로드 - 락 획득 성공 시 action을 실행한다")
    void execute_runsRunnableAction_whenLockAcquired() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);

        AtomicBoolean actionRan = new AtomicBoolean(false);
        AtomicBoolean fallbackRan = new AtomicBoolean(false);

        lockExecutor.execute("key", 3, 5,
                () -> actionRan.set(true),
                () -> fallbackRan.set(true));

        assertThat(actionRan).isTrue();
        assertThat(fallbackRan).isFalse();
    }

    @Test
    @DisplayName("void(Runnable) 오버로드 - 락 획득 실패 시 onLockUnavailable을 실행한다")
    void execute_runsRunnableFallback_whenLockNotAcquired() throws InterruptedException {
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock("key")).willReturn(rLock);
        given(rLock.tryLock(3, 5, TimeUnit.SECONDS)).willReturn(false);

        AtomicBoolean actionRan = new AtomicBoolean(false);
        AtomicBoolean fallbackRan = new AtomicBoolean(false);

        lockExecutor.execute("key", 3, 5,
                () -> actionRan.set(true),
                () -> fallbackRan.set(true));

        assertThat(actionRan).isFalse();
        assertThat(fallbackRan).isTrue();
    }
}
