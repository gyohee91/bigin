package com.ghyinc.finance.domain.loan.adaptor.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

@Builder
public record LoanLimitAdaptorResponse(
        PartnerCode partnerCode,
        boolean success,
        String failReason,
        long resTimeMs
) {
    public static LoanLimitAdaptorResponse success(
            PartnerCode partnerCode,
            long resTimeMs
    ) {
        return LoanLimitAdaptorResponse.builder()
                .partnerCode(partnerCode)
                .success(true)
                .failReason(null)
                .resTimeMs(resTimeMs)
                .build();
    }

    public static LoanLimitAdaptorResponse fail(
            PartnerCode partnerCode,
            String failReason,
            long resTimeMs
    ) {
        return LoanLimitAdaptorResponse.builder()
                .partnerCode(partnerCode)
                .success(false)
                .failReason(failReason)
                .resTimeMs(resTimeMs)
                .build();
    }
}
