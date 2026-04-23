package com.ghyinc.finance.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.dto.NotificationSendResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.event.NotificationEvent;
import com.ghyinc.finance.domain.notification.event.NotificationEventProducer;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationEventProducer notificationEventProducer;

    private final OutboxEventRepository outboxEventRepository;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final ObjectMapper objectMapper;

    public NotificationSendResponse sendNotification(NotificationSendRequest request) {
        try {
            Notification notification = Notification.builder()
                    .channelType(request.getChannelType())
                    .sendType(request.getSendType())
                    .recipient(request.getRecipient())
                    .scheduledAt(request.getSendType() == SendType.SCHEDULED ? LocalDateTime.now() : null)
                    .title(request.getTitle())
                    .content(request.getContent())
                    .build();

            notificationRepository.save(notification);


            // Outbox Insert
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .aggregateType("Notification")
                    .aggregateId(String.valueOf(notification.getId()))
                    .eventType("NOTIFICATION_SEND")
                    .payload(objectMapper.writeValueAsString(
                            NotificationEvent.from(notification)
                    ))
                    .status(OutboxStatus.PENDING)
                    .build();

            outboxEventRepository.save(outboxEvent);

            //notificationEventProducer.publish(notification);
            //notificationSenderService.call(notification);

            // Spring 이벤트 발행 -> AFTER_COMMIT 후 Kafka 발행
            applicationEventPublisher.publishEvent(
                    new OutboxCreatedEvent(outboxEvent.getId()));

            return NotificationSendResponse.from(notification);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
