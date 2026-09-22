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
     * (2026-09 갱신) inquiry()는 더 이상 @Transactional이 아니다 - 선저장 트랜잭션(짧게, commit) /
     * 파트너 팬아웃 대기(무트랜잭션) / 결과반영 트랜잭션(짧게, 새 트랜잭션)으로 분리했다
     * (LoanLimitInquiryPersistenceService 참고). 예전엔 이 스레드가 Hikari 커넥션 1개를 붙잡은 채
     * partnerApiExecutor의 파트너 팬아웃(최대 49개 병렬 호출) 완료까지 기다렸는데, 부하가 걸려
     * 파트너 응답이 느려질수록 커넥션 점유시간도 늘어나 Hikari 풀이 고갈되는 피드백 루프가
     * 근본 원인이었다. 지금은 이 풀의 maxPoolSize가 더 이상 "동시 점유 가능한 Hikari 커넥션 수의
     * 상한"이 아니다 - 순수하게 초당 유입되는 inquiry 요청을 처리할 스레드 수만 고려하면 된다.
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

    /**
     * {@code loanLimitExecutor} 포화로 Fan-out 제출이 거부됐을 때의 보상 트랜잭션
     * ({@link com.ghyinc.finance.domain.loan.service.LoanLimitEventHandler#compensateForRejection}) 전용 풀.
     * <p>
     * (2026-09 부하테스트로 발견 및 수정) 이 보상 트랜잭션을 별도 풀 없이 Tomcat 요청 스레드에서
     * 그대로 동기 실행하던 구버전에는 다음과 같은 2차 병목이 있었다: {@code loanLimitExecutor}가
     * 한번 포화되면 그 이후 유입되는 모든 요청이 전부 이 보상 경로(REQUIRES_NEW로 새 Hikari
     * 커넥션 요구)를 동시에 타게 되어, executor 포화가 곧바로 Hikari 풀 고갈로 전이되는
     * 피드백 루프가 발생했다. 실제로 20 req/s 지속 부하 테스트에서 executor 포화 후 수십 초 만에
     * Hikari 풀(150) 전체가 소진되는 것을 로그로 재현/확인했다.
     * <p>
     * 보상 트랜잭션을 이 작은 전용 풀(core=2, max=5)로 위임함으로써, 포화 상황에서도 동시에
     * 열리는 보상용 Hikari 커넥션 수 자체를 하드 캡으로 제한한다. 일부 FAILED 처리가 지연되는
     * 것은 best-effort 특성상 허용 가능하지만, 보상 로직이 Hikari 풀 전체를 끌고 내려가는 것은
     * 허용할 수 없기 때문이다. queueCapacity(200)로 순간적인 몰림은 유실 없이 순차 처리하고,
     * 그마저 넘치면(즉, 이 작은 풀조차 감당 못 할 정도의 극단적 상황) 로그만 남기고 스킵한다 -
     * markFailed()는 원래도 best-effort로 설계되어 있다.
     */
    @Bean(name = "compensationExecutor")
    public Executor compensationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(5);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("loan-limit-compensation-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
