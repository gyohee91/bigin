package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StaleInquiryRepairTest {
    @Mock
    StaleInquiryFailService staleInquiryFailService;

    InquiryPollingProperties pollingProperties =
            new InquiryPollingProperties(Duration.ofSeconds(60), Duration.ofSeconds(2));

    StaleInquiryProperties staleInquiryProperties =
            new StaleInquiryProperties(Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(5), 100, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(20));

    private StaleInquiryRepair repair() {
        return new StaleInquiryRepair(staleInquiryFailService, pollingProperties, staleInquiryProperties);
    }

    /** createdAt 이 지금으로부터 elapsed 전인 건의 deadline(epoch ms) = createdAt + clientWaitLimit(60s) */
    private long deadlineOfCreatedAgo(Duration elapsed) {
        return System.currentTimeMillis() - elapsed.toMillis() + pollingProperties.clientWaitLimit().toMillis();
    }

    @Test
    @DisplayName("stale 기준(2분)을 넘겼고 CAS가 성공하면 true")
    void repairIfDue_success() {
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(true);

        assertThat(repair().repairIfDue("LL1", InquiryStatus.IN_PROGRESS, this.deadlineOfCreatedAgo(Duration.ofMinutes(3)))).isTrue();
    }

    @Test
    @DisplayName("CAS 가 0건이면 false")
    void repairIfDue_notUpdated() {
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(false);

        assertThat(repair().repairIfDue("LL1", InquiryStatus.IN_PROGRESS, this.deadlineOfCreatedAgo(Duration.ofMinutes(3)))).isFalse();
    }

    @Test
    @DisplayName("예외가 나도 던지지 않고 false (폴링 응답은 정상, 배치가 backstop)")
    void repairIfDue_throws_returnsFalse() {
        when(staleInquiryFailService.repairIfStale("LL1")).thenThrow(new IllegalStateException("db down"));

        assertThat(repair().repairIfDue("LL1", InquiryStatus.IN_PROGRESS, this.deadlineOfCreatedAgo(Duration.ofMinutes(3)))).isFalse();
    }

    @Test
    @DisplayName("생성 후 최소 타임아웃(2분) 전이면 DB를 건드리지 않는다")
    void repairIfDue_beforeMinTimeout_skips() {
        assertThat(repair().repairIfDue("LL1", InquiryStatus.IN_PROGRESS, this.deadlineOfCreatedAgo(Duration.ofSeconds(100)))).isFalse();

        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("종료 상태는 아니다")
    void repairIfDue_terminal_skips() {
        assertThat(repair().repairIfDue("LL1", InquiryStatus.SUCCESS, this.deadlineOfCreatedAgo(Duration.ofMinutes(10)))).isFalse();

        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("deadline을 모르면(0) 판단하지 않는다")
    void repairIfDue_unknownDeadline_skips() {
        assertThat(repair().repairIfDue("LL1", InquiryStatus.IN_PROGRESS, 0L)).isFalse();

        verifyNoInteractions(staleInquiryFailService);
    }

    @Test
    @DisplayName("상태를 모르면(null) 종료 상태로 보지 않고, stale 기준이 지났으면 시도한다")
    void repairIfDue_unknownStatus_treatedAsNonTerminal() {
        when(staleInquiryFailService.repairIfStale("LL1")).thenReturn(false);

        repair().repairIfDue("LL1", null, this.deadlineOfCreatedAgo(Duration.ofMinutes(3)));

        verify(staleInquiryFailService).repairIfStale("LL1");
    }
}