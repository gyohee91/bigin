package com.ghyinc.finance.domain.loan.event;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
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
    private String name;
    private InquiryStatus status;
}
