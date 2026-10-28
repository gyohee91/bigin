package com.ghyinc.finance.domain.loan.readmodel;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class InquiryReadModelEventListener {
    private final InquiryReadModelStore readModelStore;

    /**
     * 커밋된 변경만 Redis에 반영. 롤백되면 호출되지 않는다. 실패해도 예외를 던지지 않는다(InquiryReadModelStore 내부 처리)
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(InquiryReadModelEvent event) {
        readModelStore.apply(event);
    }
}
