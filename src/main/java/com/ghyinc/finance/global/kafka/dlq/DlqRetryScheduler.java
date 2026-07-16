package com.ghyinc.finance.global.kafka.dlq;

import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DlqRetryScheduler {
    private final DlqEventRepository dlqEventRepository;

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(
            name = "DlqRetryScheduler_retry",
            lockAtLeastFor = "25s",
            lockAtMostFor = "28s"
    )
    public void retry() {
        List<DlqEvent> targets = dlqEventRepository.findRetryTarget(
                List.of(DlqStatus.PENDING, DlqStatus.RETRYING),
                LocalDateTime.now(),
                50
        );

        if(targets.isEmpty())   return;

        targets.forEach(this::retryEvent);
    }

    private void retryEvent(DlqEvent event) {

    }
}
