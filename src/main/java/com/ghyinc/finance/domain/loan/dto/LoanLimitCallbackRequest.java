package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanLimitCallbackRequest {
    @JsonProperty("preScrResultList")
    private List<PreScrResultList> preScrResultList;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreScrResultList {
        private String loReqtNo;
        private String productCode;
        private LoanLimitResultCode resultCode;
        private Long amount;
        private Double interestRate;
    }
}
