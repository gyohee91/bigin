package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BusinessLoanLimitStrategy implements LoanLimitStrategy {
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;

    @Override
    public LoanType getLoanType() {
        return LoanType.BUSINESS;
    }

    @Override
    public List<PartnerCode> getSupportedBanks() {
        return partnerLoanTypeRepository.findActivePartnerCodeByLoanType(this.getLoanType());
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
