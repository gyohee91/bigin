package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

import java.util.List;

public record PartnerDispatchPlan(
        List<PartnerCode> availablePartnerCodes,
        LoanLimitAdaptorRequest adaptorRequest) {
}
