package com.ghyinc.finance.domain.loan.adaptor;

import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.stereotype.Component;

@Component
public class KakaobankLoanLimitAdaptor implements LoanLimitAdaptor{
    @Override
    public PartnerCode getPartnerCode() {
        return PartnerCode.KAKAO_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(LoanLimitAdaptorRequest request) {
        return null;
    }
}
