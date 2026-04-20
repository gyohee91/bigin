package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import com.ghyinc.finance.global.common.DateUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PersonalLoanLimitStrategy implements LoanLimitStrategy {
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;

    @Override
    public LoanType getLoanType() {
        return LoanType.PERSONAL_CREDIT;
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
                .name(request.name())
                .rrno(request.rrno())
                .jobType(request.jobType())
                .jobName(request.jobName())
                .loanType(request.loanType())
                .agreePersonalCreditInfo(request.agreePersonalCreditInfo())
                .agreePersonalCreditTime(DateUtils.toDateTimeString(request.agreePersonalCreditTime()))
                .build();
    }

    @Override
    public boolean requiresExternalData() {
        return false;
    }
}
