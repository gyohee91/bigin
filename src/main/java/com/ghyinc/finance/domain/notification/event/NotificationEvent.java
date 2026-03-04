package com.ghyinc.finance.domain.notification.event;

import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import static com.ghyinc.finance.global.filter.RequestIdFilter.REQUEST_ID_KEY;

/**
 * Kafka 메시지 객체
 * <p>
 * [핵심 설계]
 * requestId를 payload에 포함시킨다
 * <p>
 * MDC는 ThreadLocal 기반이라 Kafka Consumer 스레드로 자동 전파되지 않는다.
 * 따라서 Producer 쪽에서 MDC의 requestId를 꺼내 메시지 payload에 담고,
 * Consumer 쪽에서 payload의 requestId를 꺼내 MDC에 다시 설정하는 방식으로
 * 스레드 경계를 넘어 requestId를 전파한다.
 */
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

    /**
     * requestId를 payload에 포함
     * Producer에서 MDC 값을 여기에 담아서 Kafka로 전송
     */
    private String requestId;

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
