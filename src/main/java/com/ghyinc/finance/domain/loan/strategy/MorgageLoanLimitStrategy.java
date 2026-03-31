package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.external.coocon.dto.KbAppraisalResult;
import com.ghyinc.finance.domain.loan.external.coocon.service.KbAppraisalService;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MorgageLoanLimitStrategy implements LoanLimitStrategy {
    private final KbAppraisalService kbAppraisalService;
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;

    @Override
    public LoanType getLoanType() {
        return LoanType.MORTGATE;
    }

    @Override
    public List<PartnerCode> getSupportedBanks() {
        return partnerLoanTypeRepository.findActivePartnerCodeByLoanType(this.getLoanType());
    }

    @Override
    public ExternalDataContext fetchExternalData(LoanLimitRequest request) {
        KbAppraisalResult result = kbAppraisalService.inquireKbAppraisal(request.getAddress());
        return ExternalDataContext.builder()
                .kbAppraisalResult(result)
                .build();
    }

    @Override
    public LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext) {
        KbAppraisalResult result = externalDataContext.kbAppraisalResult();
        return LoanLimitAdaptorRequest.builder()
                .name(request.getName())
                .rrno(request.getRrno())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .address(request.getAddress())
                .respData(result.respData())
                .build();
    }

    @Override
    public boolean requiresExternalData() {
        return true;
    }
}
