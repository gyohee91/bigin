package com.ghyinc.finance.domain.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.audit.entity.AuditLog;
import com.ghyinc.finance.domain.audit.repository.AuditLogRepository;
import com.ghyinc.finance.global.exception.OutboxEventSerializationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 감사 로그를 호출자의 트랜잭션에서 {@code audit_log}에 직접 INSERT 한다.
 *
 * <p>감사 로그의 소비자는 audit_log 적재 하나뿐이라 Outbox + Kafka 경로를 거치지 않는다.
 * 비즈니스 변경과 같은 트랜잭션이므로 유실이 없고, 이벤트당 DB 쓰기는 1회(INSERT)다.
 * 별도 트랜잭션을 열지 않고 호출자의 기존 트랜잭션에 참여하므로 반드시 {@code @Transactional}
 * 메서드 안에서 호출해야 한다.</p>
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void write(String eventType, String aggregateId, Object payload) {
        auditLogRepository.save(
                AuditLog.builder()
                        .eventType(eventType)
                        .aggregateId(aggregateId)
                        .payload(this.serialize(payload))
                        .build());
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new OutboxEventSerializationException(
                    "감사 로그 payload 직렬화 실패: " + payload.getClass().getSimpleName(), e);
        }
    }
}
