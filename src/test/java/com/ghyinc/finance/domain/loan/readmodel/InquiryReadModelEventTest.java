package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryReadModelEventTest {
    private final InquiryPollingProperties properties =
            new InquiryPollingProperties(Duration.ofSeconds(60), Duration.ofSeconds(2));

    @Test
    @DisplayName("seed는 전체 상품과 total, status를 담는다")
    void seed_shouldCarryAllProductsTotalAndStatus() {
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder().inquiryNo("LL1").build();
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR1").partnerCode(PartnerCode.TOSS_BANK).productCode("T1")
                .status(PartnerInquiryStatus.PENDING)
                .build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR2").partnerCode(PartnerCode.KAKAO_BANK).productCode("K1")
                .status(PartnerInquiryStatus.PENDING)
                .build());
        inquiry.initProductCount(2);
        inquiry.updateInquiryStatus(InquiryStatus.IN_PROGRESS);

        InquiryReadModelEvent event = InquiryReadModelEvent.seed(inquiry, properties);

        assertThat(event.seed()).isTrue();
        assertThat(event.inquiryNo()).isEqualTo("LL1");
        assertThat(event.total()).isEqualTo(2);
        assertThat(event.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(event.items()).extracting("loReqtNo").containsExactlyInAnyOrder("LR1", "LR2");
    }

    @Test
    @DisplayName("seed의 deadline은 createdAt + clientWaitLimit 의 epoch ms 이다")
    void seed_shouldCarryDeadlineFromCreatedAtPlusLimit() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder().inquiryNo("LL1").build();
        ReflectionTestUtils.setField(inquiry, "createdAt", createdAt);
        inquiry.initProductCount(1);

        InquiryReadModelEvent event = InquiryReadModelEvent.seed(inquiry, properties);

        long expected = createdAt.plusSeconds(60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(event.deadlineAtEpochMs()).isEqualTo(expected);
    }

    @Test
    @DisplayName("seed - createdAt 이 null 이면 현재 시각 기준으로 계산한다")
    void seed_nullCreatedAt_fallsBackToNow() {
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder().inquiryNo("LL1").build();
        inquiry.initProductCount(1);

        long before = System.currentTimeMillis();
        InquiryReadModelEvent event = InquiryReadModelEvent.seed(inquiry, properties);
        long after = System.currentTimeMillis();

        assertThat(event.deadlineAtEpochMs()).isBetween(before + 60_000L, after + 60_000L);
    }

    @Test
    @DisplayName("delta의 deadline은 0(설정 안 함)이라 기존 deadline 을 덮어쓰지 않는다")
    void delta_shouldHaveZeroDeadline() {
        assertThat(InquiryReadModelEvent.delta("LL1", "FAILED", List.of()).deadlineAtEpochMs()).isZero();
    }

    @Test
    @DisplayName("delta의 total은 변경 없음을 뜻하는 -1이다")
    void delta_shouldUseMinusOneForTotal() {
        InquiryReadModelEvent event = InquiryReadModelEvent.delta("LL1", null, List.of());

        assertThat(event.seed()).isFalse();
        assertThat(event.total()).isEqualTo(-1);
        assertThat(event.inquiryStatus()).isNull();
        assertThat(event.items()).isEmpty();
    }
}