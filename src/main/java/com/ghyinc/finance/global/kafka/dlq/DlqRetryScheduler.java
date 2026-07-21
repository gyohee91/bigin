package com.ghyinc.finance.global.kafka.dlq;

import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 지수 백오프 자동 재시도
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqRetryScheduler {
    private final DlqEventRepository dlqEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(
            name = "DlqRetryScheduler_retry",
            lockAtLeastFor = "25s",
            lockAtMostFor = "28s"
    )
    @Transactional
    public void retry() {
        List<DlqEvent> targets = dlqEventRepository.findRetryTarget(
                List.of(DlqStatus.PENDING, DlqStatus.RETRYING),
                LocalDateTime.now(),
                50
        );

        if (targets.isEmpty()) return;

        log.info("[DLQ 재시도] 대상 {}건", targets.size());
        targets.forEach(this::retryEvent);
    }

    private void retryEvent(DlqEvent dlqEvent) {
        if (dlqEvent.isMaxRetryExceeded()) {
            dlqEvent.markAsDead("최대 재시도 횟수(" + dlqEvent.getRetryCount() + "회) 초과");
            log.error("[DLQ] 최대 재시도 초과. id={}, topic={}", dlqEvent.getId(), dlqEvent.getTopic());
            return;
        }

        dlqEvent.markAsRetrying();

        try {
            kafkaTemplate.send(dlqEvent.getTopic(), dlqEvent.getPayload())
                    .get(5, TimeUnit.SECONDS);

            dlqEvent.markAsResolved();
            log.info("[DLQ] 재발행 성공. id={}, topic={}, retry={}회",
                    dlqEvent.getId(), dlqEvent.getTopic(), dlqEvent.getRetryCount());

        } catch (Exception e) {
            // RETRYING 상태 + 새 nextRetryAt이 이미 적용됨 → 다음 주기에 자동 재시도
            log.error("[DLQ] 재발행 실패. id={}, topic={}. 다음 주기에 재시도.",
                    dlqEvent.getId(), dlqEvent.getTopic(), e);
        }
    }
}