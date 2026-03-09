package com.ghyinc.finance.domain.loan.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanLimitCompletedEvent {
    private Long userId;
    private String loReqtNo;
}
