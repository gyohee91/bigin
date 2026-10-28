package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;

import java.util.List;

public record InquirySnapShot(
        String inquiryNo,
        int total,
        String status,
        List<LoanLimitProductResultDto> items
) {
}
