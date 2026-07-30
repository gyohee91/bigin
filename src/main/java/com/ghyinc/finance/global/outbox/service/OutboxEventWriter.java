package com.ghyinc.finance.global.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.global.exception.OutboxEventSerializationException;
import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Outbox 저장 + 발행 트리거를 한 곳에서 처리하는 공용 컴포넌트
 *
 * <p>비즈니스 트랜잭션 커밋과 원자적으로 {@link OutboxEvent}를 저장하고
 * 커밋 후 Kafka 발행을 트리거하는 {@link OutboxCreatedEvent}를 발행한다.</p>
 *
 * <p>별도 트랜잭션을 열지 않고 호출자의 기존 트랜잭션에 참여한다 -
 * 반드시 {@code @Transactional} 메서드 안에 호출해야 원자성이 보장된다.</p>
 *
 * @see OutboxEventService  AFTER_COMMIT 이후 실제 Kafka 발행 담당
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventWriter {
    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * OutboxEvent 저장하고, 트랜잭션 커밋 후 Kafka 발행을 트리거한다.
     *
     * @param aggregateType 집계 타입 (예: "LoanLimitInquiry", "Notification")
     * @param aggregateId   집계 식별자
     * @param eventType     이벤트 타입 (예: "LOAN_LIMIT_COMPLETED")
     * @param payload       Kafka로 발행할 페이로드 객체 - 내부에서 JSON으로 직렬화
     * @return  저장된 OutboxEvent
     */
    public OutboxEvent enqueue(String aggregateType, String aggregateId, String eventType, Object payload) {
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(this.serialize(payload))
                .status(OutboxStatus.PENDING)
                .build();

        OutboxEvent savedOutboxEvent = outboxEventRepository.save(outboxEvent);

        // Spring 이벤트 발행 -> AFTER_COMMIT 후 OutboxEventService가 Kafka 발행
        applicationEventPublisher.publishEvent(
                new OutboxCreatedEvent(savedOutboxEvent.getId()));

        return savedOutboxEvent;
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new OutboxEventSerializationException(
                    "Outbox payload 직렬화 실패: " + payload.getClass().getSimpleName(), e);
        }
    }
}
