package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
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
    public void validate(LoanLimitRequest request) {

    }

    @Override
    public ExternalDataContext fetchExternalData(LoanLimitRequest request) {
        return ExternalDataContext.empty();
    }

    @Override
    public LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext) {
        return LoanLimitAdaptorRequest.builder()
                .name(request.getName())
                .rrno(request.getRrno())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .loanType(request.getLoanType())
                .build();
    }

    @Override
    public boolean requiresExternalData() {
        return false;
    }
}
