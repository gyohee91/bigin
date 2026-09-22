package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * {@code @Async} 대신 {@code loanLimitExecutor}에 직접 {@code execute()}로 제출하는 이유
 * (제출 시점 {@code TaskRejectedException}을 동기적으로 잡아 보상 처리하기 위함)가
 * 실제로 지켜지는지 검증한다. loanLimitExecutor는 mock으로 대체해 정상 제출/거부 두
 * 시나리오를 직접 재현한다.
 */
@ExtendWith(MockitoExtension.class)
class LoanLimitEventHandlerTest {

    private LoanLimitEventHandler eventHandler;

    @Mock
    private Executor loanLimitExecutor;

    @Mock
    private Executor compensationExecutor;

    @Mock
    private LoanLimitSenderService loanLimitSenderService;

    @Mock
    private LoanLimitInquiryPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        eventHandler = new LoanLimitEventHandler(
                loanLimitExecutor, compensationExecutor, loanLimitSenderService, persistenceService);
    }

    // compensationExecutor(mock)에 제출된 Runnable을 테스트 스레드에서 즉시 실행시킨다.
    private void runCompensationInline() {
        willAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).given(compensationExecutor).execute(any(Runnable.class));
    }

    private LoanLimitInquiryCreatedEvent buildEvent() {
        return LoanLimitInquiryCreatedEvent.builder()
                .id(1L)
                .activePartnerCodes(List.of(PartnerCode.LINE_BANK))
                .adaptorRequest(mock(LoanLimitAdaptorRequest.class))
                .build();
    }

    @Test
    @DisplayName("handleInquiryCreated - 제출 성공 시 loanLimitExecutor로 파트너 팬아웃을 트리거한다")
    void handleInquiryCreated_submitsFanOutToExecutor() {
        LoanLimitInquiryCreatedEvent event = buildEvent();

        // 실제 스레드 풀 대신 mock Executor이므로, execute()에 전달된 작업을
        // 테스트 스레드에서 즉시 실행시켜 dispatchFanOut() 호출 여부를 검증한다.
        willAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).given(loanLimitExecutor).execute(any(Runnable.class));

        eventHandler.handleInquiryCreated(event);

        then(loanLimitSenderService).should().inquiry(
                eq(event.id()), eq(event.activePartnerCodes()), eq(event.adaptorRequest()));
        then(persistenceService).should(never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("handleInquiryCreated - loanLimitExecutor 포화(TaskRejectedException) 시 compensationExecutor로 FAILED 보상 처리를 위임한다")
    void handleInquiryCreated_executorRejected_compensatesWithFailedStatus() {
        LoanLimitInquiryCreatedEvent event = buildEvent();

        // @Async였다면 AsyncUncaughtExceptionHandler로만 전달되어 이 자리에서 못 잡았을 예외.
        // 직접 execute()를 호출하기 때문에 동기적으로 잡아 보상 처리로 이어질 수 있다.
        willThrow(new TaskRejectedException("loanLimitExecutor 큐 초과"))
                .given(loanLimitExecutor).execute(any(Runnable.class));
        runCompensationInline();

        eventHandler.handleInquiryCreated(event);

        // 보상 트랜잭션은 Tomcat 요청 스레드가 아니라 compensationExecutor로 위임돼야 한다
        // (executor 포화 시 모든 요청이 동시에 Hikari 커넥션을 새로 요구하는 것을 막기 위함)
        then(compensationExecutor).should().execute(any(Runnable.class));
        then(persistenceService).should().markFailed(event.id());
        // 제출 자체가 거부되었으므로 파트너 팬아웃은 절대 실행되지 않아야 한다
        then(loanLimitSenderService).should(never()).inquiry(anyLong(), any(), any());
    }

    @Test
    @DisplayName("handleInquiryCreated - loanLimitExecutor와 compensationExecutor가 모두 포화되면 예외 없이 best-effort로 스킵한다")
    void handleInquiryCreated_bothExecutorsRejected_skipsWithoutThrowing() {
        LoanLimitInquiryCreatedEvent event = buildEvent();

        // 극단적인 상황: 보상 전용 풀(queueCapacity 200)마저 넘치는 경우.
        // 이 경로는 Hikari 풀을 지키기 위한 하드 캡이므로, 여기서 재시도하며 커넥션을 더
        // 요구하지 않고 로그만 남긴 채 조용히 스킵되어야 한다(예외가 밖으로 새어나가면 안 됨).
        willThrow(new TaskRejectedException("loanLimitExecutor 큐 초과"))
                .given(loanLimitExecutor).execute(any(Runnable.class));
        willThrow(new TaskRejectedException("compensationExecutor 큐 초과"))
                .given(compensationExecutor).execute(any(Runnable.class));

        eventHandler.handleInquiryCreated(event);

        then(persistenceService).should(never()).markFailed(anyLong());
        then(loanLimitSenderService).should(never()).inquiry(anyLong(), any(), any());
    }

    @Test
    @DisplayName("compensateForRejection - Inquiry를 FAILED로 전환한다")
    void compensateForRejection_marksInquiryFailed() {
        LoanLimitInquiryCreatedEvent event = buildEvent();

        eventHandler.compensateForRejection(event);

        then(persistenceService).should().markFailed(event.id());
    }
}
