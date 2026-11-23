package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanLimitPollingResponseTest {

    private static final long POLL_MS = 2_000L;

    private static LoanLimitPollingResponse base() {
        return LoanLimitPollingResponse.builder()
                .inquiryNo("LL1")
                .totalProductCount(3)
                .successProductCount(1)
                .inquiryStatus("IN_PROGRESS")
                .build();
    }

    @Test
    @DisplayName("withDeadline - 진행 중이고 마감 전이면 expired=false, remainingMs 는 남은 시간")
    void inProgress_beforeDeadline() {
        long deadline = System.currentTimeMillis() + 30_000L;

        LoanLimitPollingResponse r = base().withDeadline(InquiryStatus.IN_PROGRESS, deadline, POLL_MS);

        assertThat(r.expired()).isFalse();
        assertThat(r.remainingMs()).isBetween(29_000L, 30_000L);
        assertThat(r.deadlineAtEpochMs()).isEqualTo(deadline);
        assertThat(r.pollIntervalMs()).isEqualTo(POLL_MS);
    }

    @Test
    @DisplayName("withDeadline - 진행 중이고 마감이 지났으면 expired=true, remainingMs=0(음수 아님)")
    void inProgress_pastDeadline() {
        LoanLimitPollingResponse r = base()
                .withDeadline(InquiryStatus.IN_PROGRESS, System.currentTimeMillis() - 5_000L, POLL_MS);

        assertThat(r.expired()).isTrue();
        assertThat(r.remainingMs()).isZero();
    }

    @Test
    @DisplayName("withDeadline - 마감 시각과 정확히 같아도(남은 시간 0) expired=true")
    void inProgress_exactlyAtDeadline() {
        LoanLimitPollingResponse r = base()
                .withDeadline(InquiryStatus.PENDING, System.currentTimeMillis(), POLL_MS);

        assertThat(r.expired()).isTrue();
        assertThat(r.remainingMs()).isZero();
    }

    @Test
    @DisplayName("withDeadline - 종료 상태는 마감 전이어도 remainingMs=0, expired=false")
    void terminal_beforeDeadline() {
        long deadline = System.currentTimeMillis() + 30_000L;

        for (InquiryStatus status : new InquiryStatus[]{InquiryStatus.SUCCESS, InquiryStatus.PARTIAL_SUCCESS, InquiryStatus.FAILED}) {
            LoanLimitPollingResponse r = base().withDeadline(status, deadline, POLL_MS);
            assertThat(r.expired()).as(status.name()).isFalse();
            assertThat(r.remainingMs()).as(status.name()).isZero();
        }
    }

    @Test
    @DisplayName("withDeadline - 종료 상태는 마감이 지나도 expired=false (이미 끝난 건을 '지연'으로 표시하지 않는다)")
    void terminal_pastDeadline() {
        LoanLimitPollingResponse r = base()
                .withDeadline(InquiryStatus.FAILED, System.currentTimeMillis() - 60_000L, POLL_MS);

        assertThat(r.expired()).isFalse();
        assertThat(r.remainingMs()).isZero();
    }

    @Test
    @DisplayName("withDeadline - 상태를 모르면(null) 진행 중으로 본다")
    void nullStatus_treatedAsNonTerminal() {
        LoanLimitPollingResponse r = base()
                .withDeadline(null, System.currentTimeMillis() - 1_000L, POLL_MS);

        assertThat(r.expired()).isTrue();
    }

    @Test
    @DisplayName("withDeadline - deadline 이 0 이하이면 자기 자신을 그대로 반환하고 필드는 null")
    void unknownDeadline_returnsSameInstance() {
        LoanLimitPollingResponse original = base();

        assertThat(original.withDeadline(InquiryStatus.IN_PROGRESS, 0L, POLL_MS)).isSameAs(original);
        assertThat(original.withDeadline(InquiryStatus.IN_PROGRESS, -1L, POLL_MS)).isSameAs(original);
        assertThat(original.expired()).isNull();
        assertThat(original.remainingMs()).isNull();
    }

    @Test
    @DisplayName("withDeadline - 기존 필드는 그대로 두고 마감 관련 4개 필드만 채운 새 인스턴스를 반환한다")
    void preservesOtherFields() {
        LoanLimitPollingResponse original = base();

        LoanLimitPollingResponse r = original
                .withDeadline(InquiryStatus.IN_PROGRESS, System.currentTimeMillis() + 10_000L, POLL_MS);

        assertThat(r).isNotSameAs(original);
        assertThat(r.inquiryNo()).isEqualTo("LL1");
        assertThat(r.totalProductCount()).isEqualTo(3);
        assertThat(r.successProductCount()).isEqualTo(1);
        assertThat(r.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(original.expired()).isNull();            // 원본은 불변
    }

    @Test
    @DisplayName("unchanged 응답에도 withDeadline 을 적용할 수 있다")
    void works_onUnchangedResponse() {
        LoanLimitPollingResponse r = LoanLimitPollingResponse.unchanged("LL1", 9L)
                .withDeadline(InquiryStatus.IN_PROGRESS, System.currentTimeMillis() - 1L, POLL_MS);

        assertThat(r.changed()).isFalse();
        assertThat(r.version()).isEqualTo(9L);
        assertThat(r.expired()).isTrue();
    }
}
