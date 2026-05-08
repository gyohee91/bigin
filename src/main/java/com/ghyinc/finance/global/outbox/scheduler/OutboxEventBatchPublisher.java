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

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventBatchPublisher {
    private final OutboxEventService outboxEventService;
    private final OutboxEventRepository outboxEventRepository;

    /**
     * 1분마다 실행
     * 5분 이상 PENDING 상태인 건만 재시도
     * -> 즉시 발행 실패 or 비즈니스 트랜잭션 실패 건 처리
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

        retryTargets.forEach(outboxEvent -> {
            try {
                outboxEventService.publishToKafka(outboxEvent);
                outboxEventRepository.save(outboxEvent);
            } catch (Exception e) {
                outboxEvent.markAsFailed();
            }
        });
    }
}
