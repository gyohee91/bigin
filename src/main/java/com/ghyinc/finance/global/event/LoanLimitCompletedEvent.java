package com.ghyinc.finance.global.event;

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
    private String inquiryNo;
    private Long userId;
    private String name;
    private InquiryStatus status;

    private String requestId;
}
