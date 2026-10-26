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
import java.time.LocalDateTime;
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishAfterCommit(OutboxCreatedEvent event) {
        // 이벤트가 발행에 필요한 값을 모두 들고 있으므로 방금 저장한 행을 다시 조회(findById)하지 않는다.
        // 별도 트랜잭션(REQUIRES_NEW)도 열지 않는다 - 발행 완료 UPDATE는 repository 메서드가 자기 트랜잭션으로 수행한다.
        this.publishToKafka(event.id(), event.aggregateType(), event.aggregateId(), event.payload());
    }

    public void publishToKafka(Long outboxId, String aggregateType, String aggregateId, String payload) {
        ProducerRecord<String, Object> record = this.buildRecord(
                this.resolveTopic(aggregateType), aggregateId, payload);

        try {
            kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if(ex != null) {
                            log.error("Kafka 발행 실패", ex);
                            // 실패 시 PENDING 유지. 배치가 재시도
                        } else {
                            // 성공 시 PUBLISHED UPDATE (엔티티 로드/merge 없이 id 기준 UPDATE 1건)
                            try {
                                outboxEventRepository.markAsPublished(outboxId, LocalDateTime.now());
                            } catch (Exception updateEx) {
                                // 상태 반영 실패 시 PENDING 으로 남아 배치가 재발행한다(at-least-once)
                                log.error("Outbox PUBLISHED 반영 실패. outboxId={}", outboxId, updateEx);
                            }
                            log.info("Kafka 발행 성공. partition={}",
                                    result.getRecordMetadata().partition());
                        }
                    });
        } catch (Exception e) {
            // send() 자체 실패 (브로커 연결 불가 등)
            log.error("Kafka send() 실패. outboxId={}", outboxId, e);
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
            ProducerRecord<String, Object> record = this.buildRecord(
                    this.resolveTopic(outboxEvent.getAggregateType()),
                    outboxEvent.getAggregateId(),
                    outboxEvent.getPayload());

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
     * ProducerRecord 생성 + 현재 스레드 MDC의 requestId를 Kafka 헤더로 전파한다.
     * Consumer의 RecordInterceptor가 이 헤더로 MDC를 복원한다.
     * (원 요청 스레드 컨텍스트가 없는 배치 재시도 등은 헤더 없음 -> Interceptor가 새 UUID를 발급)
     */
    private ProducerRecord<String, Object> buildRecord(String topic, String key, String payload) {
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, null, key, payload);

        String requestId = MDC.get(REQUEST_ID_KEY);
        if (requestId != null) {
            record.headers().add(REQUEST_ID_KEY, requestId.getBytes(StandardCharsets.UTF_8));
        }
        return record;
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
