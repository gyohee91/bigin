package com.ghyinc.finance.global.kafka.dlq;

import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * DLT 토픽 수신 + 자동 분류
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqEventConsumer {
    private final DlqEventRepository dlqEventRepository;
    private final PoisonPillClassifier classifier;

    @KafkaListener(
            topics = {"loan-limit-completed.DLT", "notification.send.DLT"},
            groupId = "notification-dlq-group"
    )
    public void consume(
            String payload,
            ConsumerRecord<String, String> record,
            @Header(KafkaHeaders.EXCEPTION_CAUSE_FQCN) String exceptionClass,
            @Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage
    ) {
        log.error("[DLQ] 실패 메시지 수신. topic={}, offset={}, cause={}",
                record.topic(), record.offset(), exceptionMessage);

        //Exception cause = this.resolveException(exceptionClass, exceptionMessage);
        boolean isPoisonPill = classifier.isPoisonPillByClassName(exceptionClass);

        DlqEvent dlqEvent = DlqEvent.builder()
                .topic(record.topic().replace(".DLT", ""))
                .dlqTopic(record.topic())
                .payload(payload)
                .errorMessage(exceptionMessage)
                .errorType(exceptionClass)
                .kafkaOffset(record.offset())
                .kafkaPartition(record.partition())
                .status(isPoisonPill ? DlqStatus.DEAD : DlqStatus.PENDING)
                .build();

        dlqEventRepository.save(dlqEvent);

        if(isPoisonPill) {
            log.error("[DLQ] Poison Pill 감지. 영구 보관. topic={}", record.topic());
            // Slack 알림 추가
        } else {
            log.warn("[DLQ] 일시 장애로 판단. 자동 재시도 예약. topic={}", record.topic());
        }
    }

    private Exception resolveException(String exceptionClass, String message) {
        try {
            Class<?> clazz = Class.forName(exceptionClass);
            if(Exception.class.isAssignableFrom(clazz)) {
                return (Exception) clazz.getConstructor(String.class).newInstance(message);
            }
        } catch (Exception ignored) {}
        throw new RuntimeException(message);
    }
}
