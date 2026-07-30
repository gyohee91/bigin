package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
import com.ghyinc.finance.global.exception.KafkaMessageDeserializationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private final NotificationRepository notificationRepository;

    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "notification.send",
            groupId = "notification-send-group"
    )
    @Transactional
    public void consume(String payload) {

        try {
            NotificationEvent event = objectMapper.readValue(payload, NotificationEvent.class);
            log.info("[Consumer] 메시지 수신 - id: {}", event.getId());

            Notification notification = notificationRepository.findById(event.getId())
                    .orElseThrow();

            ExternalApiResponse response = notificationSenderService.call(notification);

            if(response.isSuccess()) {
                notification.markAsSuccess(response.getResultCode());
                log.info("[Consumer] 발송 성공 - id: {}", event.getId());
            } else {
                notification.markAsFailed(response.getResultCode());
                log.warn("[Consumer] 발송 실패. id={}, code={}",
                        event.getId(), response.getResultCode());
            }
        } catch (JsonProcessingException e) {
            // NotRetryableException → DefaultErrorHandler가 즉시 DLQ로 이동
            log.error("[Consumer] 페이로드 파싱 실패. DLQ 이동. payload={}", payload, e);
            throw new KafkaMessageDeserializationException("notification.send 메시지 역직렬화 실패", e);
        } finally {
            MDC.clear();    //Consumer 스레드 재사용 시 이전 requestId 오염 방지
        }
    }

}
