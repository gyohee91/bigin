package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

public record LoanLimitProductResultDto(
        String loReqtNo,
        PartnerCode partnerCode,
        String productCode,
        LoanLimitResultCode resultCode,
        Long amount,
        double interestRate
) {
}
