package com.ghyinc.finance.global.outbox.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    @Test
    @DisplayName("markAsFailed() 호출 시 FAILED 상태로 변경 및 failCount 증가")
    void markAsFailed_changeStatusAndIncrementsCount() {
        // given
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("LoanLimitInquiry")
                .aggregateId("1")
                .eventType("LoanLimitCreated")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .build();

        // when
        outboxEvent.markAsFailed();

        // then
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outboxEvent.getFailCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("markAsFailed() 반복 호출 시 failCount 누적 증가")
    void markAsFailed_repeated_incrementCount() {
        // given
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("loanLimitInquiry")
                .aggregateId("1")
                .eventType("LoanLimitCreated")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .build();

        // when
        outboxEvent.markAsFailed();
        outboxEvent.markAsFailed();

        // then
        assertThat(outboxEvent.getFailCount()).isEqualTo(2);
    }
}