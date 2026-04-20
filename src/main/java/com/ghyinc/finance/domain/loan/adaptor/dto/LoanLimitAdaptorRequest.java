package com.ghyinc.finance.domain.loan.adaptor.dto;

import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.external.coocon.dto.RespData;
import com.ghyinc.finance.domain.external.nice.dto.AutoInfo;
import com.ghyinc.finance.domain.external.nice.dto.AutoSecondInfo;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder(toBuilder = true)
public record LoanLimitAdaptorRequest(
        List<RequestProduct> requestProducts,
        String name,
        String rrno,
        JobType jobType,
        String jobName,
        LoanType loanType,
        String carNo,
        String address,
        boolean agreePersonalCreditInfo,
        String agreePersonalCreditTime,
        AutoInfo autoInfo,
        AutoSecondInfo autoSecondInfo,
        RespData respData
) {
}
