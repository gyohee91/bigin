package com.ghyinc.finance.domain.notification.dto;

import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEvent {
    private Long id;
    private ChannelType channelType;
    private SendType sendType;
    private String recipient;
    private String title;
    private String content;

    public static NotificationEvent from(Notification notification) {
        return NotificationEvent.builder()
                .id(notification.getId())
                .channelType(notification.getChannelType())
                .sendType(notification.getSendType())
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();
    }
}
