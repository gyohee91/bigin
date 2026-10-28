package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;

public record LoanLimitProductResultDto(
        String loReqtNo,
        PartnerCode partnerCode,
        String productCode,
        PartnerInquiryStatus status,
        LoanLimitResultCode resultCode,
        Long amount,
        double interestRate
) {
}
