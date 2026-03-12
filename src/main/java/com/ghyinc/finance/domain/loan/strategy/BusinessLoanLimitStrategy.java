package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BusinessLoanLimitStrategy implements LoanLimitStrategy {
    @Override
    public LoanType getLoanType() {
        return LoanType.BUSINESS;
    }

    @Override
    public List<PartnerCode> getSupportedBanks() {
        return List.of(PartnerCode.KB_CAPITAL);
    }

    @Override
    public LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request) {
        return LoanLimitAdaptorRequest.builder()
                .name(request.getName())
                .rrno(request.getRrno())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .build();
    }
}
