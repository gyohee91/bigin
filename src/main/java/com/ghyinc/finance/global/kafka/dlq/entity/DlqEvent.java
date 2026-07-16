package com.ghyinc.finance.global.kafka.dlq.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class DlqEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Comment("원본 토픽")
    private String topic;

    @Column(nullable = false)
    @Comment("DLT 토픽")
    private String dlqTopic;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Comment("원본 메시지")
    private String payload;

    @Column(nullable = false)
    @Comment("실패 원인")
    private String errorMessage;

    @Column(nullable = false)
    @Comment("예외 클래스명")
    private String errorType;

    private Long kafkaOffset;
    private Integer kafkaPartition;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DlqStatus status;

    @Column(nullable = false)
    @Comment("재시도 횟수")
    private int retryCount;

    @Comment("다음 재시도 시각")
    private LocalDateTime nextRetryAt;
}
