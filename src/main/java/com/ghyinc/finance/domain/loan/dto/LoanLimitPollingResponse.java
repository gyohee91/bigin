package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "금리 한도조회 (Polling)")
@Builder
public record LoanLimitPollingResponse(
        @Schema(description = "업무 식별번호")
        String inquiryNo,

        // Polling 진행률 정보
        @Schema(description = "전체 상품 수")
        int totalProductCount,

        @Schema(description = "한도결과 수신 완료 수")
        int successProductCount,

        @Schema(description = "진행률")
        int progressRate,

        @Schema(description = "전체 수신 완료 여부")
        boolean allResultReceived
) {
    public static LoanLimitPollingResponse from(LoanLimitInquiry inquiry) {
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .totalProductCount(inquiry.getTotalProductCount())
                .successProductCount(inquiry.getSuccessProductCount())
                .progressRate(inquiry.getProgressRate())
                .allResultReceived(inquiry.isAllResultReceived())
                .build();
    }
}
