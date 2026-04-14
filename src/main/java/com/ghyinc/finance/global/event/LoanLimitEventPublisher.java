package com.ghyinc.finance.global.event;

public interface LoanLimitEventPublisher {
    public void publishCompletedEvent(LoanLimitCompletedEvent event);
}
