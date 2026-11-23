package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class LoanLimitInquiryResponseTest {

    private static final InquiryPollingProperties PROPS =
            new InquiryPollingProperties(Duration.ofSeconds(60), Duration.ofSeconds(2));

    private static LoanLimitInquiry inquiry(String no, LocalDateTime createdAt) {
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder().inquiryNo(no).build();
        ReflectionTestUtils.setField(inquiry, "createdAt", createdAt);   // @CreatedDate 는 영속화 시점에만 채워진다
        return inquiry;
    }

    @Test
    @DisplayName("방금 생성된 건 - remainingMs 는 0 초과이고 clientWaitLimit 이하, success=true, 폴링 간격은 설정값")
    void freshInquiry_remainingWithinLimit() {
        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(inquiry("LL_1", LocalDateTime.now()), PROPS);

        assertThat(r.inquiryNo()).isEqualTo("LL_1");
        assertThat(r.success()).isTrue();
        assertThat(r.remainingMs()).isBetween(1L, 60_000L);
        assertThat(r.pollIntervalMs()).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("생성 후 20초 경과 - remainingMs 는 약 40초")
    void partiallyElapsed_remainingShrinks() {
        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(
                inquiry("LL_1", LocalDateTime.now().minusSeconds(20)), PROPS);

        assertThat(r.remainingMs()).isBetween(39_000L, 40_000L);
    }

    @Test
    @DisplayName("마감이 이미 지난 건(60초 초과) - remainingMs 는 음수가 아니라 0")
    void pastDeadline_clampedToZero() {
        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(
                inquiry("LL_1", LocalDateTime.now().minusSeconds(61)), PROPS);

        assertThat(r.remainingMs()).isZero();
    }

    @Test
    @DisplayName("마감이 한참 지난 건(1일 전) - 여전히 0 이고 음수로 내려가지 않는다")
    void farPastDeadline_neverNegative() {
        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(
                inquiry("LL_1", LocalDateTime.now().minusDays(1)), PROPS);

        assertThat(r.remainingMs()).isZero();
        assertThat(r.deadlineAtEpochMs()).isPositive();
    }

    @Test
    @DisplayName("createdAt 이 null 이면(영속화 전) 현재 시각 기준으로 계산한다")
    void nullCreatedAt_fallsBackToNow() {
        long before = System.currentTimeMillis();
        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(inquiry("LL_1", null), PROPS);
        long after = System.currentTimeMillis();

        assertThat(r.remainingMs()).isBetween(59_000L, 60_000L);
        assertThat(r.deadlineAtEpochMs()).isBetween(before + 60_000L, after + 60_000L);
    }

    @Test
    @DisplayName("deadlineAtEpochMs 는 createdAt + clientWaitLimit 의 epoch ms 이다")
    void deadlineEpoch_isCreatedAtPlusLimit() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
        long expected = createdAt.plusSeconds(60).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(inquiry("LL_1", createdAt), PROPS);

        assertThat(r.deadlineAtEpochMs()).isEqualTo(expected);
    }

    @Test
    @DisplayName("설정값이 반영된다 - clientWaitLimit 10초 / pollInterval 500ms")
    void customProperties_areApplied() {
        InquiryPollingProperties custom = new InquiryPollingProperties(Duration.ofSeconds(10), Duration.ofMillis(500));

        LoanLimitInquiryResponse r = LoanLimitInquiryResponse.from(inquiry("LL_1", LocalDateTime.now()), custom);

        assertThat(r.remainingMs()).isBetween(1L, 10_000L);
        assertThat(r.pollIntervalMs()).isEqualTo(500L);
    }
}
