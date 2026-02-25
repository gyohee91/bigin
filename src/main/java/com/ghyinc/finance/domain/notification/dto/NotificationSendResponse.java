package com.ghyinc.finance.domain.notification.dto;

import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "알림 발송 등록(응답)")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSendResponse {
    @Schema(description = "id")
    private Long notificationId;

    @Schema(description = "채널 타입")
    private ChannelType channelType;

    public static NotificationSendResponse from(Notification notification) {
        return NotificationSendResponse.builder()
                .notificationId(notification.getId())
                .channelType(notification.getChannelType())
                .build();
    }
}
