package com.ghyinc.finance.global.outbox.service;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 트랜잭션 커밋 후 실행
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {
    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(OutboxCreatedEvent event) {
        outboxEventRepository.findById(event.getId())
                .ifPresent(this::publishToKafka);
    }

    public void publishToKafka(OutboxEvent outboxEvent) {
        kafkaTemplate.send(
                "loan-limit-completed",
                        outboxEvent.getAggregateId(),
                        outboxEvent.getPayload())
                .whenComplete((result, ex) -> {
                    if(ex != null) {
                        log.error("Kafka 발행 실패", ex);
                        // 실패 시 PENDING 유지. 배치가 재시도
                    } else {
                        // 성공 시 PUBLISHED UPDATE
                        outboxEvent.markAsPublished();
                        outboxEventRepository.save(outboxEvent);
                        log.info("Kafka 발행 성공. partition={}",
                                result.getRecordMetadata().partition());
                    }
                });
    }
}
