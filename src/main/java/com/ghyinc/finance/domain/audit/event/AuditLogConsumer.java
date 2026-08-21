package com.ghyinc.finance.domain.audit.event;

import com.ghyinc.finance.domain.audit.entity.AuditLog;
import com.ghyinc.finance.domain.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogConsumer {
    private final AuditLogRepository auditLogRepository;

    @KafkaListener(
            topics = "audit.partner-transmission",
            groupId = "audit-log-group"
    )
    public void consumeTransmission(String payload, ConsumerRecord<String, String> record) {
        this.save("PARTNER_TRANSMISSION", record.key(), payload, record);
    }

    @KafkaListener(
            topics = "audit.partner-callback",
            groupId = "audit-log-group"
    )
    public void consumeCallback(String payload, ConsumerRecord<String, String> record) {
        this.save("PARTNER_CALLBACK", record.key(), payload, record);
    }

    private void save(String eventType, String aggregateId, String payload, ConsumerRecord<String, String> record) {
        auditLogRepository.save(
                AuditLog.builder()
                        .eventType(eventType)
                        .aggregateId(aggregateId)
                        .payload(payload)
                        .build()
        );
        log.info("[AuditLog] {} 적재 완료. aggregateId={}, partition={}, offset={}",
                eventType, aggregateId, record.partition(), record.offset());
    }
}
