package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "대출 가능 금융사")
@Builder
public record AvailablePartnerSummary(
        @Schema(description = "금융사 코드")
        PartnerCode partnerCode,

        @Schema(description = "금융사명")
        String partnerName,

        @Schema(description = "대출유형")
        LoanType loanType,

        @Schema(description = "금융사 내 최저 금리")
        double lowestInterestRate,

        @Schema(description = "금융사 내 최고 한도")
        long highestAmount,

        @Schema(description = "대출 가능 상품 (한도 높은 순, 같으면 금리 낮은 순)")
        List<AvailableProductSummary> products
) {
}
