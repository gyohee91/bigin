package com.ghyinc.finance.global.outbox.event;

/**
 * Spring 내부 이벤트
 * Spring의 ApplicationEvent를 수신
 *
 * <p>발행에 필요한 값(aggregateType/aggregateId/payload)을 이벤트에 함께 담는다.
 * AFTER_COMMIT 리스너가 방금 저장한 행을 다시 SELECT(findById)하지 않기 위함이다.</p>
 *
 * @param id            저장된 OutboxEvent의 id (발행 완료 UPDATE 대상)
 * @param aggregateType 집계 타입 (Kafka 토픽 결정에 사용)
 * @param aggregateId   집계 식별자 (Kafka 메시지 key)
 * @param payload       JSON 직렬화된 페이로드 (Kafka 메시지 value)
 */
public record OutboxCreatedEvent(
        Long id,
        String aggregateType,
        String aggregateId,
        String payload
) {}
