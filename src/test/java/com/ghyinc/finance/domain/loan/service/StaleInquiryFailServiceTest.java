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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class StaleInquiryFailServiceTest {

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private StaleInquiryFailService staleInquiryFailService;

    @Test
    @DisplayName("failIfStale - CAS 가 1건 갱신되면 true 를 반환하고 FAILED 읽기모델 이벤트를 발행한다")
    void failIfStale_updated_returnsTrue_andPublishesFailedEvent() {
        LocalDateTime before = LocalDateTime.of(2026, 11, 23, 10, 0);
        given(loanLimitInquiryRepository.failIfStale(
                eq(1L), eq(InquiryStatus.IN_PROGRESS), eq(before), any(LocalDateTime.class)))
                .willReturn(1);

        boolean result = staleInquiryFailService.failIfStale(1L, "LL_1", InquiryStatus.IN_PROGRESS, before);

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

        boolean result = staleInquiryFailService.failIfStale(
                1L, "LL_1", InquiryStatus.PENDING, LocalDateTime.of(2026, 11, 23, 10, 0));

        assertThat(result).isFalse();
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("failIfStale - 기대 상태와 기준 시각을 그대로 CAS 조건으로 전달하고, updatedAt 용 now 는 호출 시각이다")
    void failIfStale_passesExpectedStatusAndBefore_andUsesCurrentTimeAsNow() {
        LocalDateTime before = LocalDateTime.of(2026, 11, 23, 10, 0);
        given(loanLimitInquiryRepository.failIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willReturn(1);

        LocalDateTime start = LocalDateTime.now();
        staleInquiryFailService.failIfStale(7L, "LL_7", InquiryStatus.ACCEPTED, before);
        LocalDateTime end = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> nowCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        then(loanLimitInquiryRepository).should().failIfStale(
                eq(7L), eq(InquiryStatus.ACCEPTED), eq(before), nowCaptor.capture());
        assertThat(nowCaptor.getValue()).isBetween(start, end);
    }

    @Test
    @DisplayName("failIfStale - 저장소 예외는 삼키지 않고 전파하며 이벤트도 발행하지 않는다 (롤백되는 건은 읽기모델에 반영하지 않는다)")
    void failIfStale_repositoryThrows_propagates_andPublishesNothing() {
        given(loanLimitInquiryRepository.failIfStale(any(), any(), any(), any(LocalDateTime.class)))
                .willThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> staleInquiryFailService.failIfStale(
                        1L, "LL_1", InquiryStatus.PENDING, LocalDateTime.of(2026, 11, 23, 10, 0)))
                .isInstanceOf(IllegalStateException.class);

        then(applicationEventPublisher).shouldHaveNoInteractions();
    }
}
