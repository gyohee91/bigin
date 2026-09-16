package com.ghyinc.finance.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@EnableAsync
@Configuration
public class AsyncConfig {
    /**
     * @Async("loanLimitExecutor") 전용
     * LoanLimitSenderService.inquiry() 처리
     * 한도조회 요청 1건 1스레드 점유
     */
    @Bean(name = "loanLimitExecutor")
    public Executor loanLimitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(30);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("loan-limit-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * CompletableFuture.supplyAsync() 전용
     * 금융사별 API 병렬 전송
     */
    @Bean(name = "partnerApiExecutor")
    public Executor partnerApiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(250);   // Σ(파트너별 Bulkhead max-concurrent-calls)
        executor.setMaxPoolSize(300);   // 순간 버스트 대비, corePoolSize의 10~20%
        executor.setQueueCapacity(30);  // 짧은 버스트만 흡수
        executor.setThreadNamePrefix("partner-api-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
