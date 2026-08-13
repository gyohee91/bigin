package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.notification.sender.NotificationSender;
import com.ghyinc.finance.domain.notification.sender.NotificationSenderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSenderService {
    private final NotificationSenderFactory notificationSenderFactory;
    private final NotificationRepository notificationRepository;

    @Transactional
    public void sendAndUpdateResult(Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow();

        ExternalApiResponse response = this.call(notification);

        if(response.isSuccess()) {
            notification.markAsSuccess(response.getResultCode());
            log.info("[Consumer] 발송 성공 - id: {}", notificationId);
        } else {
            notification.markAsFailed(response.getResultCode());
            log.warn("[Consumer] 발송 실패. id={}, code={}",
                    notificationId, response.getResultCode());
        }
    }

    public ExternalApiResponse call(Notification notification) {
        NotificationSender sender = notificationSenderFactory.getSender(notification.getChannelType());

        return sender.send(notification);
    }
}
