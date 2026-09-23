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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
    @Async("outboxPublishExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(OutboxCreatedEvent event) {
        outboxEventRepository.findById(event.id())
                .ifPresent(this::publishToKafka);
    }

    public void publishToKafka(OutboxEvent outboxEvent) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(
                this.resolveTopic(outboxEvent.getAggregateType()),
                null,
                outboxEvent.getAggregateId(),
                outboxEvent.getPayload()
        );

        // requestId를 Kafka 헤더로 전파 -> Consumer의 RecordInterceptor가 MDC 복원에 사용
        // (배치 재시도 등 원 요청 스레드 컨텍스트가 없는 경우 null -> Interceptor가 새 UUID를 발급)
        String requestId = MDC.get(REQUEST_ID_KEY);
        if (requestId != null) {
            record.headers().add(REQUEST_ID_KEY, requestId.getBytes(StandardCharsets.UTF_8));
        }

        try {
            kafkaTemplate.send(
                            this.resolveTopic(outboxEvent.getAggregateType()),
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

    /**
     * 배치 재시도 전용 - 결과를 동기적으로 기다려야 PUBLISHED/FAILED를 정확히 반영할 수 있다.
     * 즉시발행 경로(publishAfterCommit)에서는 절대 호출 금지 - loanLimitExecutor 스레드를
     * Kafka ack 대기로 묶고 Hikari 커넥션도 그동안 물게 된다.
     */
    public boolean publishToKafkaSync(OutboxEvent outboxEvent, Duration timeout) {
        try {
            ProducerRecord<String, Object> record = new ProducerRecord<>(
                    this.resolveTopic(outboxEvent.getAggregateType()),
                    null,
                    outboxEvent.getAggregateId(),
                    outboxEvent.getPayload()
            );

            kafkaTemplate.send(record)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);

            log.info("[{}] Outbox 배치 재시도 발행 성공", outboxEvent.getId());
            return true;
        } catch (Exception e) {
            log.error("[{}] Outbox 배치 재시도 발행 실패", outboxEvent.getId(), e);
            return false;
        }
    }

    /**
     * 배치 재시도 결과를 새 트랜잭션에서 반영한다. 다른 스레드(outboxRetryExecutor)에서
     * 넘어온 detached 엔티티를 그대로 쓰지 않고 id로 다시 조회한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyRetryResult(Long outboxId, boolean success) {
        outboxEventRepository.findById(outboxId).ifPresent(outboxEvent -> {
            if (success) {
                outboxEvent.markAsPublished();
            } else {
                outboxEvent.markAsFailed();
            }
        });
    }

    /**
     * aggregateType으로 Topic 분기 처리
     */
    private String resolveTopic(String aggregateType) {
        return switch (aggregateType) {
            case "LoanLimitInquiry" -> "loan-limit-completed";
            case "Notification"     -> "notification.send";
            case "PartnerTransmission"  -> "audit.partner-transmission";
            case "PartnerCallback"  -> "audit.partner-callback";
            default -> throw new InvalidRequestException(
                    "알 수 없는 aggregateType: " + aggregateType);
        };
    }
}
