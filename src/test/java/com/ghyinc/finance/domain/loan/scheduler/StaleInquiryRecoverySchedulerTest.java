package com.ghyinc.finance.domain.loan.scheduler;

import com.ghyinc.finance.domain.loan.dto.StaleInquiryView;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.service.StaleInquiryFailService;
import com.ghyinc.finance.domain.loan.config.StaleInquiryProperties;
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
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class StaleInquiryRecoverySchedulerTest {

    // 상태별로 구분되도록 서로 다른 타임아웃을 사용한다
    private static final Duration ACCEPTED_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration IN_PROGRESS_TIMEOUT = Duration.ofMinutes(3);
    private static final int BATCH_SIZE = 50;
    private static final Duration FIXED_DELAY = Duration.ofSeconds(10);
    private static final Duration MAX_RUN = Duration.ofSeconds(30);
    private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(20);
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @Mock
    private StaleInquiryFailService staleInquiryFailService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    private MeterRegistry meterRegistry;
    private StaleInquiryRecoveryScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = newScheduler(BATCH_SIZE, MAX_RUN);
        // 기본값: 모든 상태에서 대상 없음. 개별 테스트가 필요한 상태만 덮어쓴다
        lenient().when(loanLimitInquiryRepository.findStale(any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
        lenient().when(loanLimitInquiryRepository.findStaleAfter(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());
    }

    private StaleInquiryRecoveryScheduler newScheduler(int batchSize, Duration maxRunDuration) {
        return new StaleInquiryRecoveryScheduler(
                staleInquiryFailService,
                loanLimitInquiryRepository,
                new StaleInquiryProperties(ACCEPTED_TIMEOUT, PENDING_TIMEOUT, IN_PROGRESS_TIMEOUT, batchSize, maxRunDuration, FIXED_DELAY, LOCK_AT_LEAST),
                meterRegistry
        );
    }

    /** updatedAt 은 id 에 비례해 증가(오래된 순서 = id 순서) */
    private static StaleInquiryView view(long id, String inquiryNo) {
        return view(id, inquiryNo, BASE.plusSeconds(id));
    }

    private static StaleInquiryView view(long id, String inquiryNo, LocalDateTime updatedAt) {
        return new StaleInquiryView() {
            @Override
            public Long getId() {
                return id;
            }

            @Override
            public String getInquiryNo() {
                return inquiryNo;
            }

            @Override
            public LocalDateTime getUpdatedAt() {
                return updatedAt;
            }
        };
    }

    /**
     * 첫 페이지는 findStale, 두 번째부터는 findStaleAfter(keyset) 가 차례로 돌려줄 페이지를 지정한다.
     * 더 이상 지정하지 않은 호출은 기본 stub(빈 목록)이 받는다.
     */
    @SafeVarargs
    private void stubStalePages(InquiryStatus status, List<StaleInquiryView>... pages) {
        given(loanLimitInquiryRepository.findStale(eq(status), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(pages[0]);
        if (pages.length > 1) {
            given(loanLimitInquiryRepository.findStaleAfter(
                    eq(status), any(LocalDateTime.class), any(LocalDateTime.class), any(Long.class), any(Pageable.class)))
                    .willReturn(pages[1], java.util.Arrays.copyOfRange(pages, 2, pages.length));
        }
    }

    private void stubStale(InquiryStatus status, StaleInquiryView... views) {
        stubStalePages(status, List.of(views));
    }

    private double counter(String name, InquiryStatus from) {
        var counter = meterRegistry.find(name).tag("from", from.name()).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double failedCount(InquiryStatus from) {
        return counter("inquiry.recovery.failed", from);
    }

    private double errorCount(InquiryStatus from) {
        return counter("inquiry.recovery.error", from);
    }

    // ------------------------------------------------------------------ 기본 동작

    @Test
    @DisplayName("대상이 없으면 아무 것도 처리하지 않고 지표도 만들지 않는다")
    void noTargets_doesNothing() {
        scheduler.failStaleInquiries();

        then(staleInquiryFailService).shouldHaveNoInteractions();
        assertThat(meterRegistry.find("inquiry.recovery.failed").counters()).isEmpty();
        assertThat(meterRegistry.find("inquiry.recovery.error").counters()).isEmpty();
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
        then(loanLimitInquiryRepository).should(times(3)).findStale(any(), any(), captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(pageable -> {
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(BATCH_SIZE);
        });
    }

    // ------------------------------------------------------------------ 묶음 처리

    @Test
    @DisplayName("조회된 id 들을 한 번에 묶어 failAllIfStale 로 처리하며, 조회에 쓴 상태·기준 시각을 그대로 전달한다")
    void failsAllTargetsInOneBulkCall() {
        stubStale(InquiryStatus.PENDING, view(1L, "LL_1"), view(2L, "LL_2"));
        given(staleInquiryFailService.failAllIfStale(any(), any(), any())).willReturn(2);

        scheduler.failStaleInquiries();

        ArgumentCaptor<LocalDateTime> findBefore = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should().findStale(eq(InquiryStatus.PENDING), findBefore.capture(), any(Pageable.class));
        then(staleInquiryFailService).should(times(1))
                .failAllIfStale(eq(List.of(1L, 2L)), eq(InquiryStatus.PENDING), eq(findBefore.getValue()));
        then(staleInquiryFailService).should(never()).failIfStale(any(), any(), any(), any());
    }

    @Test
    @DisplayName("FAILED 처리된 건수를 상태별 카운터에 기록한다")
    void recordsFailedCountPerStatus() {
        stubStale(InquiryStatus.ACCEPTED, view(1L, "LL_1"));
        stubStale(InquiryStatus.IN_PROGRESS, view(2L, "LL_2"), view(3L, "LL_3"));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.ACCEPTED), any())).willReturn(1);
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.IN_PROGRESS), any())).willReturn(2);

        scheduler.failStaleInquiries();

        assertThat(failedCount(InquiryStatus.ACCEPTED)).isEqualTo(1.0);
        assertThat(failedCount(InquiryStatus.PENDING)).isZero();
        assertThat(failedCount(InquiryStatus.IN_PROGRESS)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("서비스가 실제 갱신한 건수만 집계한다 (CAS 로 일부가 빠지면 조회된 건수보다 적다)")
    void countsOnlyActuallyUpdatedRows() {
        stubStale(InquiryStatus.PENDING, view(1L, "LL_1"), view(2L, "LL_2"), view(3L, "LL_3"));
        given(staleInquiryFailService.failAllIfStale(any(), any(), any())).willReturn(1);

        scheduler.failStaleInquiries();

        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ 반복·종료 조건

    @Test
    @DisplayName("한 페이지가 가득 차면 keyset 커서(마지막 행의 updatedAt, id)로 다음 페이지를 읽고, 마지막 페이지가 차지 않으면 멈춘다")
    void keepsDrainingWithKeysetCursor_thenStopsOnPartialPage() {
        scheduler = newScheduler(2, MAX_RUN);
        StaleInquiryView v2 = view(2L, "LL_2");
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1"), v2),     // 가득 참 → 계속
                List.of(view(3L, "LL_3")));        // 부분 페이지 → 종료
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(2, 1);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).should(times(1))
                .findStale(eq(InquiryStatus.PENDING), any(), any(Pageable.class));
        then(loanLimitInquiryRepository).should(times(1)).findStaleAfter(
                eq(InquiryStatus.PENDING), any(), eq(v2.getUpdatedAt()), eq(2L), any(Pageable.class));
        then(staleInquiryFailService).should(times(2)).failAllIfStale(any(), eq(InquiryStatus.PENDING), any());
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(3.0);
    }

    @Test
    @DisplayName("커서는 매 페이지의 마지막 행으로 전진하고, 모든 호출은 첫 페이지 번호(0)와 batchSize 로 읽는다")
    void cursorAdvancesToLastRowOfEachPage() {
        scheduler = newScheduler(2, MAX_RUN);
        StaleInquiryView v2 = view(2L, "LL_2");
        StaleInquiryView v4 = view(4L, "LL_4");
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1"), v2),
                List.of(view(3L, "LL_3"), v4),
                List.of(view(5L, "LL_5")));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(2, 2, 1);

        scheduler.failStaleInquiries();

        ArgumentCaptor<LocalDateTime> lastAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Long> lastId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(loanLimitInquiryRepository).should(times(2)).findStaleAfter(
                eq(InquiryStatus.PENDING), any(), lastAt.capture(), lastId.capture(), pageable.capture());
        assertThat(lastAt.getAllValues()).containsExactly(v2.getUpdatedAt(), v4.getUpdatedAt());
        assertThat(lastId.getAllValues()).containsExactly(2L, 4L);
        assertThat(pageable.getAllValues()).allSatisfy(p -> {
            assertThat(p.getPageNumber()).isZero();         // offset 이 아니라 keyset 이므로 항상 0 페이지
            assertThat(p.getPageSize()).isEqualTo(2);
        });
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(5.0);
    }

    @Test
    @DisplayName("[회귀] 앞쪽 행이 계속 처리되지 않아도(진전 0) 커서가 그 뒤로 넘어가 뒤쪽 정상 건을 처리한다")
    void poisonRowsAtFront_doNotStarveLaterRows() {
        scheduler = newScheduler(2, MAX_RUN);
        StaleInquiryView v2 = view(2L, "LL_2");
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1"), v2),     // 이 두 건은 계속 처리되지 않는 행
                List.of(view(3L, "LL_3")));        // 뒤쪽 정상 건
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(0, 1);

        scheduler.failStaleInquiries();

        then(staleInquiryFailService).should().failAllIfStale(eq(List.of(1L, 2L)), eq(InquiryStatus.PENDING), any());
        then(staleInquiryFailService).should().failAllIfStale(eq(List.of(3L)), eq(InquiryStatus.PENDING), any());
        then(loanLimitInquiryRepository).should().findStaleAfter(
                eq(InquiryStatus.PENDING), any(), eq(v2.getUpdatedAt()), eq(2L), any(Pageable.class));
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("모든 페이지가 전부 실패해도(진전 0) 커서가 전진하므로 마지막 페이지에서 끝난다 (무한 루프 없음)")
    void allPagesFail_stillTerminates() {
        scheduler = newScheduler(2, MAX_RUN);
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1"), view(2L, "LL_2")),
                List.of(view(3L, "LL_3"), view(4L, "LL_4")),
                List.of(view(5L, "LL_5")));
        given(staleInquiryFailService.failAllIfStale(any(), any(), any())).willReturn(0);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).should(times(1)).findStale(eq(InquiryStatus.PENDING), any(), any(Pageable.class));
        then(loanLimitInquiryRepository).should(times(2)).findStaleAfter(
                eq(InquiryStatus.PENDING), any(), any(), any(), any(Pageable.class));
        then(staleInquiryFailService).should(times(3)).failAllIfStale(any(), eq(InquiryStatus.PENDING), any());
        assertThat(failedCount(InquiryStatus.PENDING)).isZero();
    }

    @Test
    @DisplayName("가득 찬 페이지 다음 keyset 조회가 비어 있으면 거기서 끝난다")
    void emptyPageAfterCursor_stops() {
        scheduler = newScheduler(2, MAX_RUN);
        stubStalePages(InquiryStatus.PENDING, List.of(view(1L, "LL_1"), view(2L, "LL_2")));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(2);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).should(times(1)).findStaleAfter(
                eq(InquiryStatus.PENDING), any(), any(), any(), any(Pageable.class));
        then(staleInquiryFailService).should(times(1)).failAllIfStale(any(), eq(InquiryStatus.PENDING), any());
    }

    @Test
    @DisplayName("updatedAt 이 같은 행이 경계에 있어도 커서는 (updatedAt, id) 쌍으로 마지막 행을 가리킨다")
    void cursorKeepsIdWhenUpdatedAtTies() {
        scheduler = newScheduler(2, MAX_RUN);
        LocalDateTime same = BASE.plusMinutes(1);
        StaleInquiryView second = view(2L, "LL_2", same);
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1", same), second),
                List.of(view(3L, "LL_3", same)));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(2, 1);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).should().findStaleAfter(
                eq(InquiryStatus.PENDING), any(), eq(same), eq(2L), any(Pageable.class));
    }

    @Test
    @DisplayName("커서는 상태마다 새로 시작한다 - 다른 상태의 첫 조회는 항상 findStale 이다")
    void cursorIsResetPerStatus() {
        scheduler = newScheduler(2, MAX_RUN);
        stubStalePages(InquiryStatus.ACCEPTED, List.of(view(1L, "LL_1"), view(2L, "LL_2")));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.ACCEPTED), any())).willReturn(2);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).should().findStale(eq(InquiryStatus.PENDING), any(), any(Pageable.class));
        then(loanLimitInquiryRepository).should().findStale(eq(InquiryStatus.IN_PROGRESS), any(), any(Pageable.class));
        then(loanLimitInquiryRepository).should(never())
                .findStaleAfter(eq(InquiryStatus.PENDING), any(), any(), any(), any(Pageable.class));
        then(loanLimitInquiryRepository).should(never())
                .findStaleAfter(eq(InquiryStatus.IN_PROGRESS), any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("한 사이클 안의 모든 keyset 조회는 같은 기준 시각(before)을 쓴다")
    void sameBeforeAcrossPagesInOneCycle() {
        scheduler = newScheduler(2, MAX_RUN);
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1"), view(2L, "LL_2")),
                List.of(view(3L, "LL_3")));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(2, 1);

        scheduler.failStaleInquiries();

        ArgumentCaptor<LocalDateTime> first = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> next = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should().findStale(eq(InquiryStatus.PENDING), first.capture(), any(Pageable.class));
        then(loanLimitInquiryRepository).should().findStaleAfter(
                eq(InquiryStatus.PENDING), next.capture(), any(), any(), any(Pageable.class));
        assertThat(next.getValue()).isEqualTo(first.getValue());
    }

    @Test
    @DisplayName("실행 시간 예산이 0 이면 아무 조회도 하지 않는다")
    void zeroTimeBudget_doesNothing() {
        scheduler = newScheduler(BATCH_SIZE, Duration.ZERO);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).shouldHaveNoInteractions();
        then(staleInquiryFailService).shouldHaveNoInteractions();
    }

    // ------------------------------------------------------------------ 묶음 실패 → 건별 폴백

    @Test
    @DisplayName("묶음 처리가 예외로 실패하면 같은 건들을 건별로 다시 처리하고, 에러 카운터를 올리며, 건별 성공분을 집계한다")
    void bulkFailure_fallsBackToPerRowProcessing() {
        stubStale(InquiryStatus.PENDING, view(1L, "LL_1"), view(2L, "LL_2"));
        given(staleInquiryFailService.failAllIfStale(any(), any(), any())).willThrow(new IllegalStateException("bulk failed"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any())).willReturn(true);

        scheduler.failStaleInquiries();

        // 읽기모델 이벤트에 inquiryNo 가 필요하므로 null 이 아닌 값으로 넘겨야 한다
        then(staleInquiryFailService).should().failIfStale(eq(1L), eq("LL_1"), eq(InquiryStatus.PENDING), any());
        then(staleInquiryFailService).should().failIfStale(eq(2L), eq("LL_2"), eq(InquiryStatus.PENDING), any());
        assertThat(errorCount(InquiryStatus.PENDING)).isEqualTo(1.0);   // 묶음 실패 1회
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("건별 폴백 중 한 건이 실패해도 나머지를 계속 처리하고, 실패한 건은 집계에서 제외한다")
    void perRowFallback_continuesAfterSingleFailure() {
        stubStale(InquiryStatus.PENDING, view(1L, "LL_1"), view(2L, "LL_2"), view(3L, "LL_3"));
        given(staleInquiryFailService.failAllIfStale(any(), any(), any())).willThrow(new IllegalStateException("bulk failed"));
        given(staleInquiryFailService.failIfStale(any(), any(), any(), any())).willAnswer(invocation -> {
            if (((Long) invocation.getArgument(0)) == 2L) {
                throw new IllegalStateException("poison row");
            }
            return true;
        });

        scheduler.failStaleInquiries();

        then(staleInquiryFailService).should().failIfStale(eq(1L), any(), any(), any());
        then(staleInquiryFailService).should().failIfStale(eq(2L), any(), any(), any());
        then(staleInquiryFailService).should().failIfStale(eq(3L), any(), any(), any());   // 실패 이후에도 계속
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(2.0);
        assertThat(errorCount(InquiryStatus.PENDING)).isEqualTo(2.0);   // 묶음 실패 1 + 건별 실패 1
    }

    @Test
    @DisplayName("묶음 실패 → 건별 폴백도 전부 실패해도 커서는 전진해 다음 페이지를 이어서 읽는다")
    void bulkAndPerRowFailure_stillAdvancesCursor() {
        scheduler = newScheduler(2, MAX_RUN);
        StaleInquiryView v2 = view(2L, "LL_2");
        stubStalePages(InquiryStatus.PENDING,
                List.of(view(1L, "LL_1"), v2),
                List.of(view(3L, "LL_3")));
        given(staleInquiryFailService.failAllIfStale(eq(List.of(1L, 2L)), eq(InquiryStatus.PENDING), any()))
                .willThrow(new IllegalStateException("bulk failed"));
        given(staleInquiryFailService.failIfStale(any(), any(), eq(InquiryStatus.PENDING), any()))
                .willThrow(new IllegalStateException("row failed"));
        given(staleInquiryFailService.failAllIfStale(eq(List.of(3L)), eq(InquiryStatus.PENDING), any())).willReturn(1);

        scheduler.failStaleInquiries();

        then(loanLimitInquiryRepository).should().findStaleAfter(
                eq(InquiryStatus.PENDING), any(), eq(v2.getUpdatedAt()), eq(2L), any(Pageable.class));
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(1.0);
        assertThat(errorCount(InquiryStatus.PENDING)).isEqualTo(3.0);    // 묶음 1 + 건별 2
    }

    @Test
    @DisplayName("한 상태에서 묶음·건별 모두 실패해도 다음 상태 처리는 계속된다")
    void failureInOneStatus_doesNotBlockNextStatus() {
        stubStale(InquiryStatus.ACCEPTED, view(1L, "LL_1"));
        stubStale(InquiryStatus.PENDING, view(2L, "LL_2"));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.ACCEPTED), any()))
                .willThrow(new IllegalStateException("bulk failed"));
        given(staleInquiryFailService.failIfStale(any(), any(), eq(InquiryStatus.ACCEPTED), any()))
                .willThrow(new IllegalStateException("row failed"));
        given(staleInquiryFailService.failAllIfStale(any(), eq(InquiryStatus.PENDING), any())).willReturn(1);

        scheduler.failStaleInquiries();

        then(staleInquiryFailService).should().failAllIfStale(eq(List.of(2L)), eq(InquiryStatus.PENDING), any());
        assertThat(failedCount(InquiryStatus.PENDING)).isEqualTo(1.0);
        assertThat(failedCount(InquiryStatus.ACCEPTED)).isZero();
    }

    // ------------------------------------------------------------------ 스케줄 설정

    @Test
    @DisplayName("@Scheduled 주기와 @SchedulerLock 최소 보유 시간은 설정(placeholder)으로 읽고, 최대 보유 시간은 5분이다")
    void scheduleAnnotations_useConfigurableValues() throws NoSuchMethodException {
        var method = StaleInquiryRecoveryScheduler.class.getMethod("failStaleInquiries");

        var scheduled = method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        var lock = method.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);

        assertThat(scheduled.fixedDelayString()).contains("app.inquiry-recovery.fixed-delay");
        assertThat(lock.lockAtLeastFor()).contains("app.inquiry-recovery.lock-at-least");
        assertThat(lock.lockAtMostFor()).isEqualTo("5m");
    }
}
