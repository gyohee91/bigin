package com.ghyinc.finance.domain.kcbcredit.entity;

import com.ghyinc.finance.domain.kcbcredit.enums.KcbCreditFileStatus;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class KcbCreditFile extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Comment("원본 파일명")
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Comment("처리 상태")
    @Builder.Default
    private KcbCreditFileStatus status = KcbCreditFileStatus.RECEIVED;

    @Comment("전체 레코드 수")
    private Long totalCount;

    @Comment("잔액 변동 대상 건수")
    private Long targetCount;

    @Comment("알림 발송 요청 건수")
    private Long notifiedCount;

    @Comment("매칭 실패/스킵 건수")
    private Long skippedCount;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    @Column(columnDefinition = "TEXT")
    @Comment("에러메시지")
    private String errorMessage;

    public void markProcessing() {
        this.status = KcbCreditFileStatus.PROCESSING;
        this.startedAt = LocalDateTime.now();
    }

    public void markCompleted(long total, long target, long notified, long skipped) {
        this.status = KcbCreditFileStatus.COMPLETED;
        this.totalCount = total;
        this.targetCount = target;
        this.notifiedCount = notified;
        this.skippedCount = skipped;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = KcbCreditFileStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }
}
