package com.ghyinc.finance.global.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * loanLimitExecutor, partnerApiExecutor 스레드풀(활성 스레드, 큐 크기, completed task 수) 상태를
 * Micrometer로 노출한다. corePoolSize/maxPoolSize 튜닝이 실축으로 맞았는지,
 * 큐가 실제로 얼마나 차는지 확인하는 용도
 * executor.active: 활성 스레드
 * executor.pool.size, executor.queued: 큐 대기 작업 수
 */
@Component
public class TaskExecutorMetrics {
    // 빈 이름 기준으로 주입 (AsyncConfig의 @Bean(name = "...")과 매칭)
    private final Executor loanLimitExecutor;
    private final Executor partnerApiExecutor;
    private final MeterRegistry meterRegistry;

    public TaskExecutorMetrics(
            @Qualifier("loanLimitExecutor") Executor loanLimitExecutor,
            @Qualifier("partnerApiExecutor") Executor partnerApiExecutor,
            MeterRegistry meterRegistry) {
        this.loanLimitExecutor = loanLimitExecutor;
        this.partnerApiExecutor = partnerApiExecutor;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void bindMetrics() {
        this.bind("loanLimitExecutor", loanLimitExecutor);
        this.bind("partnerApiExecutor", partnerApiExecutor);
    }

    public void bind(String name, Executor executor) {
        if(executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            ExecutorServiceMetrics.monitor(
                    meterRegistry,
                    taskExecutor.getThreadPoolExecutor(),   // 내부 java.util.concurrent.ThreadPoolExecutor 추출
                    name
            );
        }
    }
}
