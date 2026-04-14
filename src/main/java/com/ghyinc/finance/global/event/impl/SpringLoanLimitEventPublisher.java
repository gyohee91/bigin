package com.ghyinc.finance.global.event.impl;

import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.global.event.LoanLimitEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringLoanLimitEventPublisher implements LoanLimitEventPublisher {
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void publishCompletedEvent(LoanLimitCompletedEvent event) {
        eventPublisher.publishEvent(event);
    }
}
