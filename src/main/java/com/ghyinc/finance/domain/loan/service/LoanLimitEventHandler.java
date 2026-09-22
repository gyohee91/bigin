package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.Executor;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoanLimitEventHandler {
    private final Executor loanLimitExecutor;
    private final LoanLimitSenderService loanLimitSenderService;
    private final LoanLimitInquiryPersistenceService persistenceService;

    /**
     * {@link LoanLimitInquiryCreatedEvent} 수신 후 한도조회 비동기 전송을 시작한다.
     *
     * <p>{@code @TransactionalEventListener(AFTER_COMMIT)}을 통해 부모 트랜잭션
     * (Inquiry INSERT)이 커밋된 이후에만 실행을 보장한다. 커밋 전 실행 시 금융사
     * API 응답(콜백)이 먼저 도착해도 Inquiry를 조회할 수 없는 Race Condition이
     * 발생할 수 있기 때문이다.</p>
     *
     * <p>이 메서드에는 일부러 {@code @Async}를 붙이지 않는다(아래 주석 처리된 이유).
     * 대신 {@code loanLimitExecutor}에 직접 {@code execute()}로 작업을 제출한다.
     * {@code @Async}로 위임했다면 스레드 풀/큐가 가득 찼을 때 발생하는
     * {@code TaskRejectedException}이 별도의 {@code AsyncUncaughtExceptionHandler}로만
     * 전달되어, 이 이벤트(inquiryId)를 알고 있는 이 자리에서 곧바로 보상 처리를 하기
     * 어렵다. 그래서 제출 자체는 동기로 수행해 예외를 여기서 직접 잡고,
     * {@link #compensateForRejection}으로 FAILED 전환 및 락 해제를 수행한다.</p>
     *
     * <p>제출이 정상적으로 이루어지면 실제 파트너 팬아웃(HTTP 호출들)은
     * {@code loanLimitExecutor} 스레드에서 수행되므로, 이 메서드는 그 즉시 반환되어
     * 커밋을 트리거한 트랜잭션 스레드를 오래 붙잡지 않는다.</p>
     *
     * @param event inquiryId, 금융사 목록, 어댑터 요청 DTO를 포함한 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleInquiryCreated(LoanLimitInquiryCreatedEvent event) {
        try {
            loanLimitExecutor.execute(() -> this.dispatchFanOut(event));
        } catch(TaskRejectedException e) {
            log.error("[{}] loanLimitExecutor 포화로 Fan-out 제출 실패 - FAILED 전환 및 락 해제",
                    event.id(), e);
            this.compensateForRejection(event);
        }
    }

    private void dispatchFanOut(LoanLimitInquiryCreatedEvent event) {
        // loanLimitExecutor 스레드에서 실행된다: 실제 파트너 팬아웃(병렬 HTTP 호출)을 트리거
        loanLimitSenderService.inquiry(
                event.id(),
                event.activePartnerCodes(),
                event.adaptorRequest()
        );
    }

    // AFTER_COMMIT 시점이라 활성 트랜잭션이 없으므로 새 트랜잭션으로 명시
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateForRejection(LoanLimitInquiryCreatedEvent event) {
        persistenceService.markFailed(event.id());
        // 필요하면 여기서 사용자 알림/아웃박스 이벤트도 함께 발행
    }
}
