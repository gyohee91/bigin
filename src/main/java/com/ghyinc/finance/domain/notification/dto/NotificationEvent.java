package com.ghyinc.finance.domain.notification.dto;

import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import static com.ghyinc.finance.global.filter.RequestIdFilter.REQUEST_ID_KEY;

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
    private String requestId;

    /**
     * requestId를 payload에 포함
     * Producer에서 MDC 값을 여기에 담아서 Kafka로 전송
     */
    public static NotificationEvent from(Notification notification) {
        return NotificationEvent.builder()
                .id(notification.getId())
                .channelType(notification.getChannelType())
                .sendType(notification.getSendType())
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                //MDC에서 requestId를 꺼내 payload에 포함
                .requestId(MDC.get(REQUEST_ID_KEY))
                .build();
    }
}
