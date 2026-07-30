package com.ghyinc.finance.global.event;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.slf4j.MDC;

import static com.ghyinc.finance.global.filter.RequestIdFilter.REQUEST_ID_KEY;

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

    public static LoanLimitCompletedEvent from(LoanLimitInquiry loanLimitInquiry) {
        return LoanLimitCompletedEvent.builder()
                .inquiryNo(loanLimitInquiry.getInquiryNo())
                .userId(loanLimitInquiry.getUserId())
                .name(loanLimitInquiry.getName())
                .status(loanLimitInquiry.getStatus())
                .requestId(MDC.get(REQUEST_ID_KEY))
                .build();
    }
}
