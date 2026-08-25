package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
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
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitCompletedEventConsumer {
    private final NotificationService notificationService;
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
            topics = "loan-limit-completed",
            groupId = "loan-limit-completed-group"
    )
    public void consume(String payload, ConsumerRecord<String, String> record) {

        try {
            LoanLimitCompletedEvent event = objectMapper.readValue(payload, LoanLimitCompletedEvent.class);
            log.info("[Consumer] 한도조회 완료 이벤트 수신. inquiryNo={}, partition={}, offset={}",
                    event.getInquiryNo(), record.partition(), record.offset());

            notificationService.sendNotification(
                    NotificationSendRequest.builder()
                            .userId(event.getUserId())
                            .channelType(ChannelType.SMS)
                            .sendType(SendType.IMMEDIATE)
                            .title("한도조회 완료")
                            .content(this.buildContent(event.getStatus()))
                            .build()
            );

        } catch (JsonProcessingException e) {
            log.error("[Consumer] 페이로드 파싱 실패. DLQ 이동. topic={}, partition={}, offset={}, payload={}",
                    record.topic(), record.partition(), record.offset(), payload, e);
            throw new KafkaMessageDeserializationException("loan-limit-completed 메시지 역직렬화 실패", e);
        } finally {
            MDC.clear();
        }
    }

    private String buildContent(InquiryStatus status) {
        return switch (status) {
            case SUCCESS -> "한도조회가 완료되었습니다. 결과를 확인해보세요";
            case FAILED -> "한도조회 중 오류가 발생했습니다. 다시 시도해주세요.";
            default -> "한도조회 상태가 업데이트되었습니다";
        };
    }

    @DltHandler
    public void handleDlt(
            String payload,
            ConsumerRecord<String, String> record,
            @Header(value = KafkaHeaders.EXCEPTION_FQCN, required = false) String exceptionFqcn,
            @Header(value = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String exceptionMessage
    ) {
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
