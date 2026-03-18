package com.ghyinc.finance.domain.loan.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanLimitResultResponse {
    private String resultCode;
    private String resultMessage;

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
