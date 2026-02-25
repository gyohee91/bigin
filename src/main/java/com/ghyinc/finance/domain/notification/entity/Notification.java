package com.ghyinc.finance.domain.notification.entity;

import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Notification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Comment(value = "채널 타입")
    private ChannelType channelType;

    @Enumerated(EnumType.STRING)
    @Comment(value = "발송 타입")
    private SendType sendType;

    @Comment(value = "수신자")
    private String recipient;

    @Comment(value = "예약 시간")
    private LocalDateTime scheduledAt;

    @Comment(value = "제목")
    private String title;

    @Comment(value = "내용")
    private String content;
}
