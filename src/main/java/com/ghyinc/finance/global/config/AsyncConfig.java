package com.ghyinc.finance.global.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

@EnableAsync
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
        //MDC 전파: 부모 스레드의 MDC 컨텍스트를 자식 스레드에 복사
        //requestId 등 로그 추적 컨텍스트가 병렬 처리 스레드에서도 유지됨
        executor.setTaskDecorator(task -> {
            Map<String, String> parentMdcContext = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    if(Objects.nonNull(parentMdcContext)) {
                        MDC.setContextMap(parentMdcContext);
                    }
                    task.run();
                } finally {
                    //스레드풀 스레드는 재사용되므로 반드시 초기화
                    MDC.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
