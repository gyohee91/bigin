package com.ghyinc.finance.domain.loan.scheduler;

import com.ghyinc.finance.domain.loan.dto.StaleInquiryView;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.service.StaleInquiryFailService;
import com.ghyinc.finance.global.config.StaleInquiryProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class StaleInquiryRecoverySchedulerTest {

    // 상태별로 구분되도록 서로 다른 타임아웃을 사용한다
    private static final Duration ACCEPTED_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(3);
    private static final int BATCH_SIZE = 50;

    @Mock
    private StaleInquiryFailService staleInquiryFailService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    private MeterRegistry meterRegistry;
    private StaleInquiryRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new StaleInquiryRecoveryScheduler(
                staleInquiryFailService,
                loanLimitInquiryRepository,
                new StaleInquiryProperties(ACCEPTED_TIMEOUT, PENDING_TIMEOUT, IN_PROGRESS_TIMEOUT, BATCH_SIZE),
                meterRegistry
        );
        // 기본값: 모든 상태에서 대상 없음. 개별 테스트가 필요한 상태만 덮어쓴다
        lenient().when(loanLimitInquiryRepository.findStale(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
    }

    private static StaleInquiryView view(long id, String inquiryNo) {
        return new StaleInquiryView() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getInquiryNo() {
                return inquiryNo;
            }
        };
    }

    private void stubStale(InquiryStatus status, StaleInquiryView... views) {
        given(loanLimitInquiryRepository.findStale(eq(status), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(List.of(views));
    }

    private double failedCount(InquiryStatus from) {
        var counter = meterRegistry.find("inquiry.recovery.failed").tag("from", from.name()).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("대상이 없으면 아무 것도 처리하지 않고 지표도 만들지 않는다")
    void noTargets_doesNothing() {
        scheduler.failStaleInquiries();

        then(staleInquiryFailService).shouldHaveNoInteractions();
        assertThat(meterRegistry.find("inquiry.recovery.failed").counters()).isEmpty();
    }

    @Test
    @DisplayName("ACCEPTED → PENDING → IN_PROGRESS 순서로 조회한다")
    void queriesStatusesInOrder() {
        scheduler.failStaleInquiries();

        InOrder inOrder = inOrder(loanLimitInquiryRepository);
        inOrder.verify(loanLimitInquiryRepository).findStale(eq(InquiryStatus.ACCEPTED), any(), any(Pageable.class));
        inOrder.verify(loanLimitInquiryRepository).findStale(eq(InquiryStatus.PENDING), any(), any(Pageable.class));
        inOrder.verify(loanLimitInquiryRepository).findStale(eq(InquiryStatus.IN_PROGRESS), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("상태별로 서로 다른 타임아웃으로 기준 시각(before)을 계산한다")
    void usesPerStatusTimeoutForBefore() {
        LocalDateTime start = LocalDateTime.now();
        scheduler.failStaleInquiries();
        LocalDateTime end = LocalDateTime.now();

        assertBeforeIsNowMinus(InquiryStatus.ACCEPTED, ACCEPTED_TIMEOUT, start, end);
        assertBeforeIsNowMinus(InquiryStatus.PENDING, PENDING_TIMEOUT, start, end);
        assertBeforeIsNowMinus(InquiryStatus.IN_PROGRESS, IN_PROGRESS_TIMEOUT, start, end);
    }

    private void assertBeforeIsNowMinus(InquiryStatus status, Duration timeout, LocalDateTime start, LocalDateTime end) {
        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should().findStale(eq(status), captor.capture(), any(Pageable.class));
        assertThat(captor.getValue()).isBetween(start.minus(timeout), end.minus(timeout));
    }

    @Test
    @DisplayName("조회 시 첫 페이지를 batchSize 만큼 가져온다")
    void usesBatchSizeAsPageSize() {
        scheduler.failStaleInquiries();

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        then(loanLimitInquiryRepository).should(org.mockito.Mockito.times(3))
                .findStale(any(), any(), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(pageable -> {
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(BATCH_SIZE);
        });
    }

    @Test
    @DisplayName("대상 건마다 조회에 사용한 상태·기준 시각으로 failIfStale 을 호출한다")
    void callsFailIfStaleForEachTarget_withSameStatusAndBefore() {
        stubStale(InquiryStatus.PENDING, view(1L, "LL_1"), view(2L, "LL_2"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any())).willReturn(true);

        scheduler.failStaleInquiries();

        ArgumentCaptor<LocalDateTime> findBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should().findStale(eq(InquiryStatus.PENDING), findBefore.capture(), any(Pageable.class));
        then(staleInquiryFailService).should().failIfStale(1L, "LL_1", InquiryStatus.PENDING, findBefore.getValue());
        then(staleInquiryFailService).should().failIfStale(2L, "LL_2", InquiryStatus.PENDING, findBefore.getValue());
    }

    @Test
    @DisplayName("FAILED 처리된 건수를 상태별 카운터에 기록한다")
    void recordsFailedCountPerStatus() {
        stubStale(InquiryStatus.ACCEPTED, view(1L, "LL_1"));
        stubStale(InquiryStatus.IN_PROGRESS, view(2L, "LL_2"), view(3L, "LL_3"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any())).willReturn(true);

        scheduler.failStaleInquiries();

        assertThat(failedCount(InquiryStatus.ACCEPTED)).isEqualTo(1.0);
        assertThat(failedCount(InquiryStatus.PENDING)).isZero();
        assertThat(failedCount(InquiryStatus.IN_PROGRESS)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("CAS 가 0건(그 사이 정상 진행되었거나 다른 인스턴스가 처리)이면 집계하지 않는다")
    void notCountedWhenCasLost() {
        stubStale(InquiryStatus.PENDING, view(1L, "LL_1"), view(2L, "LL_2"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any()))
                .willAnswer(invocation -> ((Long) invocation.getArgument(0)) == 2L);   // 2번만 성공

        scheduler.failStaleInquiries();

        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("한 건 처리 중 예외가 나도 나머지 건과 다음 상태를 계속 처리하고, 실패한 건은 집계에서 제외한다")
    void exceptionOnOneTarget_continuesWithOthers() {
        stubStale(InquiryStatus.ACCEPTED, view(1L, "LL_1"), view(2L, "LL_2"), view(3L, "LL_3"));
        stubStale(InquiryStatus.PENDING, view(4L, "LL_4"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any())).willAnswer(invocation -> {
            if (((Long) invocation.getArgument(0)) == 2L) {
                throw new IllegalStateException("db error");
            }
            return true;
        });

        scheduler.failStaleInquiries();

        then(staleInquiryFailService).should().failIfStale(eq(1L), any(), any(), any());
        then(staleInquiryFailService).should().failIfStale(eq(2L), any(), any(), any());
        then(staleInquiryFailService).should().failIfStale(eq(3L), any(), any(), any());   // 예외 이후에도 계속
        then(staleInquiryFailService).should().failIfStale(eq(4L), any(), any(), any());   // 다음 상태도 처리
        assertThat(failedCount(InquiryStatus.ACCEPTED)).isEqualTo(2.0);
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("해당 상태에 대상이 없으면 그 상태에 대해서는 failIfStale 을 호출하지 않는다")
    void emptyStatus_skipsFailIfStale() {
        stubStale(InquiryStatus.IN_PROGRESS, view(9L, "LL_9"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any())).willReturn(true);

        scheduler.failStaleInquiries();

        then(staleInquiryFailService).should().failIfStale(eq(9L), any(), eq(InquiryStatus.IN_PROGRESS), any());
        then(staleInquiryFailService).should(never()).failIfStale(any(), any(), eq(InquiryStatus.ACCEPTED), any());
        then(staleInquiryFailService).should(never()).failIfStale(any(), any(), eq(InquiryStatus.PENDING), any());
    }
}
