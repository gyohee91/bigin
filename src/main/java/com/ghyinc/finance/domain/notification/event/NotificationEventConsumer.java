package com.ghyinc.finance.domain.notification.event;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.NotificationEvent;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {
    private final NotificationSenderService notificationSenderService;
    private final NotificationRepository notificationRepository;

    @KafkaListener(
            topics = "notification.send",
            groupId = "notification-group"
    )
    public void consume(NotificationEvent event) {
        String requestId = Optional.ofNullable(event.getRequestId())
                .orElse(UUID.randomUUID().toString());

        try {
            MDC.put(REQUEST_ID_KEY, requestId);

            log.info("[Consumer] 메시지 수신 - id: {}", event.getId());

            Notification notification = notificationRepository.findById(event.getId())
                    .orElseThrow();

            //이 호출 내부 로그에도 requestId가 자동으로 찍힘
            ExternalApiResponse response = notificationSenderService.call(notification);

            if(response.isSuccess()) {
                notification.markAsSuccess(response.getResultCode());
                log.info("[Consumer] 발송 성공 - id: {}", event.getId());
            }
            else if("UNAVAILABLE".equals(response.getResultCode())) {
                notification.markAsFailed(response.getResultCode());
            }
            else {
                notification.markAsFailed(response.getResultCode());
            }
        } finally {
            MDC.clear();
        }
    }
}
