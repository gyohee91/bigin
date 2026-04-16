package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "대출신청 (요청)")
public record LoanApplyRequest(
        @Schema(description = "고객ID", example = "1")
        String userId,

        @Schema(description = "신청번호", example = "LR20260311A3F2C891")
        String loReqtNo,

        @Schema(description = "금융사 코드", example = "LINE_BANK")
        PartnerCode partnerCode,

        @Schema(description = "상품 코드", example = "P060100206")
        String productCode
) {}
