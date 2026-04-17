package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "한도 결과 목록 (Polling)")
@Builder
public record LoanLimitProductResultResponse(
        @Schema(description = "신청번호")
        String loReqtNo,

        @Schema(description = "금융사 코드")
        PartnerCode partnerCode,

        @Schema(description = "상품 코드")
        String productCode,

        @Schema(description = "상품 처리상태")
        PartnerInquiryStatus status,

        @Schema(description = "한도조회 결과 코드")
        LoanLimitResultCode resultCode,

        @Schema(description = "한도금액")
        Long amount,

        @Schema(description = "금리")
        double interestRate
) {
    public static LoanLimitProductResultResponse from(LoanLimitProductResult productResult) {
        return LoanLimitProductResultResponse.builder()
                .loReqtNo(productResult.getLoReqtNo())
                .partnerCode(productResult.getPartnerCode())
                .productCode(productResult.getProductCode())
                .status(productResult.getStatus())
                .resultCode(productResult.getResultCode())
                .amount(productResult.getAmount())
                .interestRate(productResult.getInterestRate())
                .build();
    }
}
