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
     * <p>
     * 주의: inquiry()는 @Transactional이라 이 스레드가 Hikari 커넥션 1개를 붙잡은 채로
     * partnerApiExecutor에서 도는 파트너 팬아웃(최대 49개 병렬 호출)의 완료까지 기다린다.
     * 즉 이 풀의 maxPoolSize가 곧 "동시에 점유 가능한 Hikari 커넥션 수의 상한"이기도 하다 -
     * Hikari maximum-pool-size를 올릴 땐 이 값도 같이 고려해야 한다(반대도 마찬가지).
     * 별도 트랜잭션 분리(선저장 트랜잭션 / 팬아웃-대기 / 결과반영 트랜잭션)로 커넥션 점유 시간을
     * 줄이는 리팩터링이 근본적으로는 더 맞지만, 지금은 사이징만 맞춘다.
     * <p>
     * 목표 20 req/s, 파트너 호출(300ms) 기준 W≈1s로 잡으면 Little's Law로 L=20×1=20 -
     * 버스트 여유를 감안해 max=40으로 설정.
     */
    @Bean(name = "loanLimitExecutor")
    public Executor loanLimitExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(15);
        executor.setMaxPoolSize(40);
        executor.setQueueCapacity(60);
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
     * <p>
     * 50개 파트너 활성화 이후 재산정: 조회 1건당 최대 49개 REST 파트너를 동시에 호출하므로,
     * 목표 20 req/s × 49 팬아웃 × 응답시간 ~300ms 가정 시 Little's Law로
     * L = 20 × 49 × 0.3 ≈ 294 - corePoolSize를 300으로 올리고, 순간 버스트 대비
     * maxPoolSize는 partnerConnectionManager의 maxTotal(400)과 맞춰 400으로 둔다.
     */
    @Bean(name = "partnerApiExecutor")
    public Executor partnerApiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(300);   // Little's Law: 20 req/s × 49 팬아웃 × 0.3s ≈ 294
        executor.setMaxPoolSize(400);    // partnerConnectionManager maxTotal과 동일하게
        executor.setQueueCapacity(50);   // 짧은 버스트만 흡수
        executor.setThreadNamePrefix("partner-api-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
