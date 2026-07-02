package com.ghyinc.finance.global.outbox.service;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
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

    /**
     * 트랜잭션 커밋 후 즉시 Kafka 발행
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(OutboxCreatedEvent event) {
        outboxEventRepository.findById(event.id())
                .ifPresent(this::publishToKafka);
    }

    public void publishToKafka(OutboxEvent outboxEvent) {
        // aggregateType으로 Topic 분기 처리
        String topic = switch (outboxEvent.getAggregateType()) {
            case "LoanLimitInquiry" -> "loan-limit-completed";
            case "Notification"     -> "notification.send";
            default -> throw new InvalidRequestException(
                    "알 수 없는 aggregateType: " + outboxEvent.getAggregateType());
        };

        try {
            kafkaTemplate.send(
                            topic,
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
        } catch (Exception e) {
            // send() 자체 실패 (브로커 연결 불가 등)
            log.error("Kafka send() 실패. outboxId={}", outboxEvent.getId(), e);
            // PENDING 유지 -> 배치 재시도
        }
    }
}
