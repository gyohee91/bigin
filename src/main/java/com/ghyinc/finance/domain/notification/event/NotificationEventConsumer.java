package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
import com.ghyinc.finance.global.exception.KafkaMessageDeserializationException;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer
 * <p>
 * requestId MDC 전파는 KafkaConfig의 RecordInterceptor가 처리한다
 * (리스너 호출 전 MDC.put, 호출 후 성공/실패 무관하게 MDC.clear)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {
    private final NotificationSenderService notificationSenderService;
    private final DlqEventRepository dlqEventRepository;

    private final ObjectMapper objectMapper;

    @RetryableTopic(
            attempts = "4",     // 최초 1회 + 재시도 3회
            backoff = @Backoff(delayExpression = "1000", multiplierExpression = "2.0", maxDelayExpression = "10000", random = true),
            listenerContainerFactory = "retryableTopicListenerContainerFactory",
            autoCreateTopics = "true",
            numPartitions = "6",     // 재시도 토픽 병렬 처리
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            exclude = { KafkaMessageDeserializationException.class, IllegalArgumentException.class }
    )
    @KafkaListener(
            topics = "notification.send",
            groupId = "notification-send-group"
    )
    public void consume(String payload, ConsumerRecord<String, String> record) {

        try {
            NotificationEvent event = objectMapper.readValue(payload, NotificationEvent.class);
            log.info("[Consumer] 메시지 수신 - id: {}, partition={}, offset={}",
                    event.getId(), record.partition(), record.offset());

            notificationSenderService.sendAndUpdateResult(event.getId());
        } catch (JsonProcessingException e) {
            // NotRetryableException → DefaultErrorHandler가 즉시 DLQ로 이동
            log.error("[Consumer] 페이로드 파싱 실패. DLQ 이동. topic={}, partition={}, offset={}, payload={}",
                    record.topic(), record.partition(), record.offset(), payload, e);
            throw new KafkaMessageDeserializationException("notification.send 메시지 역직렬화 실패", e);
        } finally {
            MDC.clear();    //Consumer 스레드 재사용 시 이전 requestId 오염 방지
        }
    }

    @DltHandler
    public void handleDlt(
            String payload,
            ConsumerRecord<String, String> record,
            @Header(value = KafkaHeaders.EXCEPTION_FQCN, required = false) String exceptionFqcn,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage) {
        try {
            log.error("[DLT] 최종 처리 실패. topic={}, partition={}, offset={}, cause={}",
                    record.topic(), record.partition(), record.offset(), exceptionMessage);

            dlqEventRepository.save(
                    DlqEvent.builder()
                            .topic(record.topic())
                            .dlqTopic(record.topic())
                            .payload(payload)
                            .errorType(exceptionFqcn)
                            .errorMessage(exceptionMessage)
                            .kafkaOffset(record.offset())
                            .kafkaPartition(record.partition())
                            .status(DlqStatus.DEAD)
                            .build()
            );
        } catch (Exception e) {
            log.error("[DLT] 최종 실패 기록 자체가 실패. 수동 확인 필요. topic={}, offset={}, payload={}",
                    record.topic(), record.offset(), payload, e);
        }

    }

}
