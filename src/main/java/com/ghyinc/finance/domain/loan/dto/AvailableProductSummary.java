package com.ghyinc.finance.domain.loan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "대출 가능 상품")
@Builder
public record AvailableProductSummary(
        @Schema(description = "상품명")
        String productCode,

        @Schema(description = "상품명")
        String productName,

        @Schema(description = "한도금액")
        Long amount,

        @Schema(description = "금리")
        double interestRate
) {
}
