package com.ghyinc.finance.domain.notification.event;

import com.ghyinc.finance.domain.notification.dto.NotificationEvent;
import com.ghyinc.finance.domain.notification.entity.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationEventProducer {
    private final KafkaTemplate<String, NotificationEvent> kafkaTemplate;

    public void publish(Notification notification) {
        NotificationEvent event = NotificationEvent.from(notification);
        kafkaTemplate.send("notification.send", String.valueOf(notification.getId()), event);
    }

}
