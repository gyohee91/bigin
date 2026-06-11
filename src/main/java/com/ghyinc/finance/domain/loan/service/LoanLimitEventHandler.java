package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class LoanLimitEventHandler {
    private final LoanLimitSenderService loanLimitSenderService;

    /**
     * {@link LoanLimitInquiryCreatedEvent} 수신 후 한도조회 비동기 전송을 시작한다.
     *
     * <p>{@code @TransactionalEventListener(AFTER_COMMIT)}을 통해 부모 트랜잭션
     * (Inquiry INSERT)이 커밋된 이후에만 실행을 보장한다. 커밋 전 실행 시 금융사
     * API 응답(콜백)이 먼저 도착해도 Inquiry를 조회할 수 없는 Race Condition이
     * 발생할 수 있기 때문이다.</p>
     *
     * <p>{@code @Async("loanLimitExecutor")}로 HTTP 요청 스레드를 즉시 해제하여
     * FE에 202 Accepted를 반환한다.</p>
     *
     * @param event inquiryId, 금융사 목록, 어댑터 요청 DTO를 포함한 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("loanLimitExecutor")
    public void handleInquiryCreated(LoanLimitInquiryCreatedEvent event) {
        loanLimitSenderService.inquiry(
                event.id(),
                event.activePartnerCodes(),
                event.adaptorRequest()
        );
    }
}
