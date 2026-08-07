package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.LoanApply;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "대출신청 (응답)")
@Builder
public record LoanApplyResponse(
        @Schema(description = "신청번호")
        String loReqtNo,

        @Schema(description = "성공 여부")
        boolean success,

        @Schema(description = "브릿지 url")
        String bridgePage
) {
    public static LoanApplyResponse from(LoanApply loanApply) {
        return LoanApplyResponse.builder()
                .loReqtNo(loanApply.getLoReqtNo())
                .build();
    }
}
