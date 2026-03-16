package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.JobType;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record LoanLimitAdaptorRequest(
        List<RequestProduct> requestProducts,
        String name,
        String rrno,
        JobType jobType,
        String jobName
) {
}
