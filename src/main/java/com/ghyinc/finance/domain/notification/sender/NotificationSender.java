package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;

/**
 * 전략 인터페이스
 */
public interface NotificationSender {
    ChannelType getChannelType();

    ExternalApiResponse send(Notification notification);
}
