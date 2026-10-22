package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "상품별 대출 가능 금융사 오퍼")
@Builder
public record ProductOfferSummary(
        @Schema(description = "금융사 코드")
        PartnerCode partnerCode,
        @Schema(description = "금융사명")
        String partnerName,
        @Schema(description = "상품코드")
        String productCode,
        @Schema(description = "한도금액")
        Long amount,
        @Schema(description = "금리")
        double interestRate
) {
}
