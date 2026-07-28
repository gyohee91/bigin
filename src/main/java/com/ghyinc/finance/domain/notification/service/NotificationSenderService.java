package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.sender.NotificationSender;
import com.ghyinc.finance.domain.notification.sender.NotificationSenderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSenderService {
    private final NotificationSenderFactory notificationSenderFactory;

    public ExternalApiResponse call(Notification notification) {
        NotificationSender sender = notificationSenderFactory.getSender(notification.getChannelType());

        return sender.send(notification);
    }
}
