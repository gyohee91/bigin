package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "금리 한도조회 (응답)")
@Builder
public record LoanLimitInquiryResponse(
        @Schema(description = "업무 식별번호")
        String inquiryNo,

        @Schema(description = "성공 여부")
        boolean success
) {
    public static LoanLimitInquiryResponse from(LoanLimitInquiry inquiry) {
        return LoanLimitInquiryResponse.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .success(true)
                .build();
    }
}
