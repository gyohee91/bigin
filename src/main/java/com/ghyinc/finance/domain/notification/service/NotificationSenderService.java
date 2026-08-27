package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
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
    private final NotificationRepository notificationRepository;

    public void sendAndUpdateResult(Long notificationId) {
        int claimed = notificationRepository.claimForSending(notificationId);
        if(claimed == 0) {
            log.warn("[Consumer] 이미 처리(중)인 알림이라 재발송 스킵. id={} - Kafka 재전달/중복 소비로 추정", notificationId);
            return;
        }

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
        notificationRepository.save(notification);
    }

    public ExternalApiResponse call(Notification notification) {
        NotificationSender sender = notificationSenderFactory.getSender(notification.getChannelType());

        return sender.send(notification);
    }
}
