package com.ghyinc.finance.domain.loan.adaptor;

import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TossbankLoanLimitAdaptor implements LoanLimitAdaptor {
    @Override
    public PartnerCode getPartnerCode() {
        return PartnerCode.TOSS_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(LoanLimitAdaptorRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            long resTimeMs = System.currentTimeMillis() - startTime;

            //External API

            log.info("[{}] 한도조회 성공, resTimeMs={}", PartnerCode.TOSS_BANK, resTimeMs);

            return LoanLimitAdaptorResponse.success(
                    PartnerCode.TOSS_BANK,
                    resTimeMs
            );
        } catch (Exception e) {
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.error("[{}] 한도조회 오류 발생", PartnerCode.TOSS_BANK, e);
            return LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, e.getMessage(), resTimeMs);
        }
    }
}
