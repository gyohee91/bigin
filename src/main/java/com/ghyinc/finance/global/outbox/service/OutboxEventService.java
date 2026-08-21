package com.ghyinc.finance.global.outbox.service;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;

import static com.ghyinc.finance.global.common.LoggingConstants.REQUEST_ID_KEY;

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
            case "PartnerTransmission"  -> "audit.partner-transmission";
            case "PartnerCallback"  -> "audit.partner-callback";
            default -> throw new InvalidRequestException(
                    "알 수 없는 aggregateType: " + outboxEvent.getAggregateType());
        };

        ProducerRecord<String, Object> record = new ProducerRecord<>(
                topic, null, outboxEvent.getAggregateId(), outboxEvent.getPayload());

        // requestId를 Kafka 헤더로 전파 -> Consumer의 RecordInterceptor가 MDC 복원에 사용
        // (배치 재시도 등 원 요청 스레드 컨텍스트가 없는 경우 null -> Interceptor가 새 UUID를 발급)
        String requestId = MDC.get(REQUEST_ID_KEY);
        if (requestId != null) {
            record.headers().add(REQUEST_ID_KEY, requestId.getBytes(StandardCharsets.UTF_8));
        }

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
