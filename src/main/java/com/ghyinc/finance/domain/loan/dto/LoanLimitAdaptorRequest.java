package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.JobType;
import lombok.Builder;

@Builder
public record LoanLimitAdaptorRequest(
        String name,
        String rrno,
        JobType jobType
) {
}
