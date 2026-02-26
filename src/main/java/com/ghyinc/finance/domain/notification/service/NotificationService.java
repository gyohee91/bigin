package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.dto.NotificationSendResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final NotificationSenderService notificationSenderService;

    public NotificationSendResponse sendNotification(NotificationSendRequest request) {
        Notification notification = Notification.builder()
                .channelType(request.getChannelType())
                .sendType(request.getSendType())
                .recipient(request.getRecipient())
                .scheduledAt(request.getSendType() == SendType.SCHEDULED ? LocalDateTime.now() : null)
                .title(request.getTitle())
                .content(request.getContent())
                .build();

        notificationRepository.save(notification);

        notificationSenderService.call(notification);

        return NotificationSendResponse.from(notification);
    }

}
