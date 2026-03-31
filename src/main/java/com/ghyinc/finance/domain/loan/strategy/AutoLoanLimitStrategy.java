package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.external.nice.dto.NiceDnrResult;
import com.ghyinc.finance.domain.loan.external.nice.service.NiceDnrService;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class AutoLoanLimitStrategy implements LoanLimitStrategy{
    private final NiceDnrService niceDnrService;
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;

    @Override
    public LoanType getLoanType() {
        return LoanType.AUTO;
    }

    @Override
    public List<PartnerCode> getSupportedBanks() {
        return partnerLoanTypeRepository.findActivePartnerCodeByLoanType(this.getLoanType());
    }

    @Override
    public ExternalDataContext fetchExternalData(LoanLimitRequest request) {
        NiceDnrResult niceDnrResult = niceDnrService.inquireNiceDnr(request.getCarNo(), request.getName());
        return ExternalDataContext.builder()
                .niceDnrResult(niceDnrResult)
                .build();
    }

    @Override
    public LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext) {
        NiceDnrResult niceDnrResult = externalDataContext.niceDnrResult();
        return LoanLimitAdaptorRequest.builder()
                .name(request.getName())
                .rrno(request.getRrno())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .carNo(request.getCarNo())
                .autoInfo(niceDnrResult.autoInfo())
                .autoSecondInfo(niceDnrResult.autoSecondInfo())
                .build();
    }

    @Override
    public boolean requiresExternalData() {
        return true;
    }
}
