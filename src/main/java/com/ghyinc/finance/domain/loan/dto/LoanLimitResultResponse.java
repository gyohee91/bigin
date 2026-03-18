package com.ghyinc.finance.domain.loan.dto;

import lombok.Builder;

@Builder
public record LoanLimitResultResponse(
        String resultCode,
        String resultMessage
) implements ResultResponse {

    public static LoanLimitResultResponse success() {
        return LoanLimitResultResponse.builder()
                .resultCode("SUCCESS")
                .build();
    }

    public static LoanLimitResultResponse fail(String resultMessage) {
        return LoanLimitResultResponse.builder()
                .resultCode("FAIL")
                .resultMessage(resultMessage)
                .build();
    }
}
