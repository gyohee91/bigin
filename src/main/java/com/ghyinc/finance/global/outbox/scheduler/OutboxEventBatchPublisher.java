package com.ghyinc.finance.global.outbox.scheduler;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import com.ghyinc.finance.global.outbox.service.OutboxEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventBatchPublisher {
    private final OutboxEventService outboxEventService;
    private final OutboxEventRepository outboxEventRepository;
    private final Executor outboxRetryExecutor;

    private static final Duration RETRY_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 1분마다 실행
     * <p>
     * (기존 버전의 문제) publishToKafka()가 fire-and-forget이라 이 메서드가 결과를
     * 기다리지 않고 리턴해버려서, 장애가 1분(스케줄 주기)보다 길게 지속되면 같은
     * PENDING 건이 다음 틱에 또 집혀 중복 발행될 수 있었다. 지금은 get()으로 결과를
     * 확정 지은 뒤에만 상태를 반영하므로 한 틱 안에서 발행-확정이 끝난다.
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(
            name = "OutboxEventBatchPublisher_retryPendingEvents",
            lockAtLeastFor = "50s",     // 최소 50초 Lock 유지 (중복 실행 방지)
            lockAtMostFor = "55s"       // 최대 55초 후 Lock 해제
    )
    public void retryPendingEvents() {
        List<OutboxEvent> retryTargets = outboxEventRepository.findRetryTargets(
                OutboxStatus.PENDING,
                LocalDateTime.now().minusMinutes(5),
                100
        );

        if(retryTargets.isEmpty())
            return;

        log.info("Outbox 재시도 대상: {} 건", retryTargets.size());

        List<CompletableFuture<Void>> futures = retryTargets.stream()
                        .map(outboxEvent -> CompletableFuture
                                .supplyAsync(
                                        () -> outboxEventService.publishToKafkaSync(outboxEvent, RETRY_TIMEOUT),
                                        outboxRetryExecutor
                                )
                                .thenAccept(success -> outboxEventService.applyRetryResult(outboxEvent.getId(), success))
                        ).toList();
        futures.forEach(CompletableFuture::join);
    }
}
