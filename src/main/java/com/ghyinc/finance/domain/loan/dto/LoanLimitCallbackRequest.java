package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class LoanLimitCallbackRequest {
    @JsonProperty("requestProductResult")
    private List<RequestProductResult> requestProductResult;

    @Getter
    @NoArgsConstructor
    public static class RequestProductResult {
        private String loReqtNo;
        private String productCode;
        private Long amount;
        private Double interestRate;
    }
}
