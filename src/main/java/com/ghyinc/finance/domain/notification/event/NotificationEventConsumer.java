package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

import static com.ghyinc.finance.global.filter.RequestIdFilter.REQUEST_ID_KEY;

/**
 * Kafka Consumer
 * <p>
 * [MDC 전파 핵심 포인트]
 * Kafka Consumer는 별도의 스레드에서 실행된다.
 * HTTP 요청 스레드의 MDC 값은 이 스레드로 자동 전파되지 않는다.
 * <p>
 * 해결책:
 * 1. event.getRequestId()로 payload에서 requestId를 꺼냄.
 * 2. MDC.put()으로 현재 Consumer 스레드의 MDC에 설정
 * 3. 이후 notificationSenderService 로그에도 같은 requestId가 찍힘
 * 4. finally에서 MDC.clear() -> Consumer 스레드 재사용 시 오염 방지
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
            groupId = "notification-group"
    )
    public void consume(String payload) {

        try {
            NotificationEvent event = objectMapper.readValue(payload, NotificationEvent.class);

            // payload에서 requestId 복원 -> Consumer 스레드 MDC에 설정
            String requestId = Optional.ofNullable(event.getRequestId())
                    .orElse(UUID.randomUUID().toString());  //Producer에서 누락된 경우
            MDC.put(REQUEST_ID_KEY, requestId);

            log.info("[Consumer] 메시지 수신 - id: {}", event.getId());

            Notification notification = notificationRepository.findById(event.getId())
                    .orElseThrow();

            //이 호출 내부 로그에도 requestId가 자동으로 찍힘
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
            throw new RuntimeException(e);
        } finally {
            MDC.clear();    //Consumer 스레드 재사용 시 이전 requestId 오염 방지
        }
    }

}
