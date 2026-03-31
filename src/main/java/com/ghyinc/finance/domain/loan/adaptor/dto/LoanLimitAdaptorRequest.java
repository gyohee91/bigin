package com.ghyinc.finance.domain.loan.adaptor.dto;

import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.external.coocon.dto.RespData;
import com.ghyinc.finance.domain.loan.external.nice.dto.AutoInfo;
import com.ghyinc.finance.domain.loan.external.nice.dto.AutoSecondInfo;
import lombok.Builder;

import java.util.List;

@Builder(toBuilder = true)
public record LoanLimitAdaptorRequest(
        List<RequestProduct> requestProducts,
        String name,
        String rrno,
        JobType jobType,
        String jobName,
        String carNo,
        String address,
        AutoInfo autoInfo,
        AutoSecondInfo autoSecondInfo,
        RespData respData
) {
}
