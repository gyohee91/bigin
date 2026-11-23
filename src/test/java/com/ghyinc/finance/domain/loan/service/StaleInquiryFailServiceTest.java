package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelEvent;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class StaleInquiryFailServiceTest {

    private static final LocalDateTime BEFORE = LocalDateTime.of(2026, 11, 23, 10, 0);

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private StaleInquiryFailService staleInquiryFailService;

    // ------------------------------------------------------------------ failIfStale (건별)

    @Test
    @DisplayName("failIfStale - CAS 가 1건 갱신되면 true 를 반환하고 FAILED 읽기모델 이벤트를 발행한다")
    void failIfStale_updated_returnsTrue_andPublishesFailedEvent() {
        given(loanLimitInquiryRepository.failIfStale(
                eq(1L), eq(InquiryStatus.IN_PROGRESS), eq(BEFORE), any(LocalDateTime.class)))
                .willReturn(1);

        boolean result = staleInquiryFailService.failIfStale(1L, "LL_1", InquiryStatus.IN_PROGRESS, BEFORE);

        assertThat(result).isTrue();
        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        InquiryReadModelEvent event = captor.getValue();
        assertThat(event.inquiryNo()).isEqualTo("LL_1");
        assertThat(event.inquiryStatus()).isEqualTo(InquiryStatus.FAILED.name());
        assertThat(event.seed()).isFalse();             // 전체 스냅샷이 아닌 delta
        assertThat(event.total()).isEqualTo(-1);        // total 변경 없음
        assertThat(event.items()).isEmpty();
    }

    @Test
    @DisplayName("failIfStale - 그 사이 상태가 바뀌어 0건이면 false, 이벤트는 발행하지 않는다")
    void failIfStale_notUpdated_returnsFalse_andPublishesNothing() {
        given(loanLimitInquiryRepository.failIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willReturn(0);

        boolean result = staleInquiryFailService.failIfStale(1L, "LL_1", InquiryStatus.PENDING, BEFORE);

        assertThat(result).isFalse();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("failIfStale - 기대 상태와 기준 시각을 그대로 CAS 조건으로 전달하고, updatedAt 용 now 는 호출 시각이다")
    void failIfStale_passesExpectedStatusAndBefore_andUsesCurrentTimeAsNow() {
        given(loanLimitInquiryRepository.failIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willReturn(1);

        LocalDateTime start = LocalDateTime.now();
        staleInquiryFailService.failIfStale(7L, "LL_7", InquiryStatus.ACCEPTED, BEFORE);
        LocalDateTime end = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should().failIfStale(
                eq(7L), eq(InquiryStatus.ACCEPTED), eq(BEFORE), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isBetween(start, end);
    }

    @Test
    @DisplayName("failIfStale - 저장소 예외는 삼키지 않고 전파하며 이벤트도 발행하지 않는다")
    void failIfStale_repositoryThrows_propagates_andPublishesNothing() {
        given(loanLimitInquiryRepository.failIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> staleInquiryFailService.failIfStale(1L, "LL_1", InquiryStatus.PENDING, BEFORE))
                .isInstanceOf(IllegalStateException.class);

        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    // ------------------------------------------------------------------ failAllIfStale (묶음)

    @Test
    @DisplayName("failAllIfStale - id 목록이 비어 있으면 0 을 반환하고 저장소·이벤트에 접근하지 않는다")
    void failAllIfStale_emptyIds_doesNothing() {
        int result = staleInquiryFailService.failAllIfStale(List.of(), InquiryStatus.PENDING, BEFORE);

        assertThat(result).isZero();
        then(loanLimitInquiryRepository).shouldHaveNoInteractions();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("failAllIfStale - 갱신이 0건이면 0 을 반환하고 재조회·이벤트 발행을 하지 않는다")
    void failAllIfStale_nothingUpdated_returnsZero_withoutReselectOrEvents() {
        given(loanLimitInquiryRepository.failAllIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willReturn(0);

        int result = staleInquiryFailService.failAllIfStale(List.of(1L, 2L), InquiryStatus.PENDING, BEFORE);

        assertThat(result).isZero();
        then(loanLimitInquiryRepository).should(never()).findInquiryNosFailedSince(any(), any());
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("failAllIfStale - 갱신 건수를 반환하고, 방금 FAILED 가 된 건마다 읽기모델 이벤트를 발행한다")
    void failAllIfStale_updated_returnsCount_andPublishesEventPerInquiry() {
        List<Long> ids = List.of(1L, 2L);
        given(loanLimitInquiryRepository.failAllIfStale(
                eq(ids), eq(InquiryStatus.IN_PROGRESS), eq(BEFORE), any(LocalDateTime.class)))
                .willReturn(2);
        given(loanLimitInquiryRepository.findInquiryNosFailedSince(eq(ids), any(LocalDateTime.class)))
                .willReturn(List.of("LL_1", "LL_2"));

        int result = staleInquiryFailService.failAllIfStale(ids, InquiryStatus.IN_PROGRESS, BEFORE);

        assertThat(result).isEqualTo(2);
        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should(org.mockito.Mockito.times(2)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(InquiryReadModelEvent::inquiryNo)
                .containsExactly("LL_1", "LL_2");
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.inquiryStatus()).isEqualTo(InquiryStatus.FAILED.name());
            assertThat(event.seed()).isFalse();
            assertThat(event.items()).isEmpty();
        });
    }

    @Test
    @DisplayName("failAllIfStale - UPDATE 의 now 와 재조회의 since 는 같은 값이며, 밀리초로 절삭되어 있다")
    void failAllIfStale_usesSameMillisTruncatedTimestampForUpdateAndReselect() {
        List<Long> ids = List.of(1L);
        given(loanLimitInquiryRepository.failAllIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willReturn(1);
        given(loanLimitInquiryRepository.findInquiryNosFailedSince(any(), any(LocalDateTime.class)))
                .willReturn(List.of("LL_1"));

        LocalDateTime start = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        staleInquiryFailService.failAllIfStale(ids, InquiryStatus.PENDING, BEFORE);
        LocalDateTime end = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should()
                .failAllIfStale(eq(ids), eq(InquiryStatus.PENDING), eq(BEFORE), nowCaptor.capture());
        then(loanLimitInquiryRepository).should()
                .findInquiryNosFailedSince(eq(ids), sinceCaptor.capture());

        assertThat(sinceCaptor.getValue()).isEqualTo(nowCaptor.getValue());
        // DB 정밀도에 따른 반올림으로 >= 비교가 어긋나지 않도록 밀리초 단위여야 한다
        assertThat(nowCaptor.getValue().getNano() % 1_000_000).isZero();
        assertThat(nowCaptor.getValue()).isBetween(start, end);
    }

    @Test
    @DisplayName("failAllIfStale - 재조회된 건수가 갱신 건수와 달라도(다른 경로가 먼저 처리한 경우 등) 반환값은 갱신 건수, 이벤트는 재조회된 건수만큼이다")
    void failAllIfStale_returnsUpdatedCount_evenIfReselectedCountDiffers() {
        given(loanLimitInquiryRepository.failAllIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willReturn(3);
        given(loanLimitInquiryRepository.findInquiryNosFailedSince(any(), any(LocalDateTime.class)))
                .willReturn(List.of("LL_1", "LL_2"));

        int result = staleInquiryFailService.failAllIfStale(List.of(1L, 2L, 3L), InquiryStatus.PENDING, BEFORE);

        assertThat(result).isEqualTo(3);
        then(applicationEventPublisher).should(org.mockito.Mockito.times(2)).publishEvent(any(InquiryReadModelEvent.class));
    }

    @Test
    @DisplayName("failAllIfStale - 저장소 예외는 전파하고 이벤트는 발행하지 않는다 (스케줄러가 건별 처리로 전환할 수 있게)")
    void failAllIfStale_repositoryThrows_propagates_andPublishesNothing() {
        given(loanLimitInquiryRepository.failAllIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> staleInquiryFailService.failAllIfStale(List.of(1L), InquiryStatus.PENDING, BEFORE))
                .isInstanceOf(IllegalStateException.class);

        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    // ------------------------------------------------------------------ 트랜잭션 경계

    @Test
    @DisplayName("두 처리 메서드 모두 @Transactional 이다 (UPDATE 와 재조회·이벤트 발행이 한 트랜잭션이어야 한다)")
    void bothMethods_areTransactional() throws NoSuchMethodException {
        assertThat(StaleInquiryFailService.class
                .getMethod("failIfStale", Long.class, String.class, InquiryStatus.class, LocalDateTime.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(StaleInquiryFailService.class
                .getMethod("failAllIfStale", List.class, InquiryStatus.class, LocalDateTime.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }
}
