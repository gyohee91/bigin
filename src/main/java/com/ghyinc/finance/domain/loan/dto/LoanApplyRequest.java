package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

@Schema(description = "대출신청(요청)")
@Getter
public class LoanApplyRequest {
    @Schema(description = "고객ID", example = "1")
    private String userId;

    @Schema(description = "신청번호", example = "LR20260311A3F2C891")
    private String loReqtNo;

    @Schema(description = "금융사 코드", example = "LINE_BANK")
    private PartnerCode partnerCode;

    @Schema(description = "상품 코드", example = "P060100206")
    private String productCode;
}
