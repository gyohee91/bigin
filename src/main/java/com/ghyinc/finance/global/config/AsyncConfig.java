package com.ghyinc.finance.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {
    /**
     * 한도조회 전용 스레드풀
     *
     * <p>외부 금융사 API I/O가 공통 WAS 스레드 풀을 점유하지 않도록 격리
     * 연동 금융사 수 * 예상 동시 요청 수를 고려하여 corePoolSize 산정
     */
    @Bean(name = "loanLimitExecutor")
    public Executor loanLimitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("loan-limit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
