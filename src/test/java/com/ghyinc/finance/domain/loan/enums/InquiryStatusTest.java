package com.ghyinc.finance.domain.loan.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryStatusTest {

    @ParameterizedTest
    @EnumSource(value = InquiryStatus.class, names = {"SUCCESS", "PARTIAL_SUCCESS", "FAILED"})
    @DisplayName("isTerminal - 결과가 확정된 상태(SUCCESS, PARTIAL_SUCCESS, FAILED)는 true")
    void isTerminal_true(InquiryStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = InquiryStatus.class, names = {"ACCEPTED", "PENDING", "IN_PROGRESS"})
    @DisplayName("isTerminal - 진행 중인 상태(ACCEPTED, PENDING, IN_PROGRESS)는 false")
    void isTerminal_false(InquiryStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("isTerminal - 모든 상태가 위 두 그룹 중 하나에 속한다 (상태가 추가되면 이 테스트가 알려준다)")
    void everyStatusIsClassified() {
        EnumSet<InquiryStatus> terminal = EnumSet.of(
                InquiryStatus.SUCCESS, InquiryStatus.PARTIAL_SUCCESS, InquiryStatus.FAILED);
        EnumSet<InquiryStatus> active = EnumSet.of(
                InquiryStatus.ACCEPTED, InquiryStatus.PENDING, InquiryStatus.IN_PROGRESS);

        EnumSet<InquiryStatus> all = EnumSet.copyOf(terminal);
        all.addAll(active);

        assertThat(all).containsExactlyInAnyOrder(InquiryStatus.values());
    }
}
