package com.ghyinc.finance.domain.notification.event;

import com.ghyinc.finance.domain.notification.dto.NotificationEvent;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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
        Notification notification = notificationRepository.findById(event.getId())
                .orElseThrow();

        notificationSenderService.call(notification);
    }
}
