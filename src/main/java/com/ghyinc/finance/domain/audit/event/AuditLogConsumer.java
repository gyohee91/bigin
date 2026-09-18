package com.ghyinc.finance.domain.audit.event;

import com.ghyinc.finance.domain.audit.entity.AuditLog;
import com.ghyinc.finance.domain.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogConsumer {
    private final AuditLogRepository auditLogRepository;

    // 두 리스너가 같은 groupId(audit-log-group)를 쓰지만 Spring Kafka는 @KafkaListener 메서드당
    // 별도의 KafkaConsumer를 만든다. group.instance.id를 application.yaml의 전역값
    // (${HOSTNAME}-${random.uuid})에 그대로 맡기면 같은 JVM 안의 두 컨슈머가 완전히 동일한
    // (group.id, group.instance.id) 쌍으로 동시에 broker에 등록을 시도하게 되고, static
    // membership 규약상 나중에 join한 쪽이 먼저 것을 FENCED_INSTANCE_ID로 밀어낸다.
    // 리스너별로 instance.id를 다르게 override해서 이 충돌을 없앤다.
    @KafkaListener(
            topics = "audit.partner-transmission",
            groupId = "audit-log-group",
            containerFactory = "batchKafkaListenerContainerFactory",
            properties = "group.instance.id=${HOSTNAME:bigin-customer}-audit-log-transmission-${random.uuid}"
    )
    public void consumeTransmission(List<ConsumerRecord<String, String>> records) {
        this.saveAll("PARTNER_TRANSMISSION", records);
    }

    @KafkaListener(
            topics = "audit.partner-callback",
            groupId = "audit-log-group",
            containerFactory = "batchKafkaListenerContainerFactory",
            properties = "group.instance.id=${HOSTNAME:bigin-customer}-audit-log-callback-${random.uuid}"
    )
    public void consumeCallback(List<ConsumerRecord<String, String>> records) {
        this.saveAll("PARTNER_CALLBACK", records);
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

    private void saveAll(String eventType, List<ConsumerRecord<String, String>> records) {
        List<AuditLog> auditLogs = records.stream()
                .map(record -> AuditLog.builder()
                        .eventType(eventType)
                        .aggregateId(record.key())
                        .payload(record.value())
                        .build())
                .toList();

        auditLogRepository.saveAll(auditLogs);

        log.info("[AuditLog] {} 배치 적재 완료. count={}, partitions={}",
                eventType,
                auditLogs.size(),
                records.stream().map(ConsumerRecord::partition).distinct().toList());
    }
}
