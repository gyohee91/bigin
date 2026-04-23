package com.ghyinc.finance.global.outbox.entity;

import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

@Entity
@Table(
        indexes = {
                @Index(
                        name = "idx_outbox_status_created_at",
                        columnList = "status, created_at"
                )
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OutboxEvent extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    @Comment("이벤트 타입")
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Comment("이벤트 payload (JSON)")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Comment("발행 상태")
    private OutboxStatus status;

    @Comment("발행 완료 시간")
    private LocalDateTime publishedAt;

    @Comment("발행 실패 횟수")
    private int failCount;

    /**
     * Kafka 발행 성공
     */
    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * Kafka 발행 실패
     */
    public void markAsFailed() {
        this.status = OutboxStatus.FAILED;
        this.failCount++;
    }
}
