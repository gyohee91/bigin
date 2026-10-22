package com.ghyinc.finance.domain.loan.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "상품별 대출 가능 금융사 그룹")
@Builder
public record ProductGroupSummary(
        @Schema(description = "상품명")
        String productName,
        @Schema(description = "상품 내 최저 금리")
        double lowestInterestRate,
        @Schema(description = "상품 내 최고 한도")
        long highestInterestAmount,
        @Schema(description = "한도 높은 순 정렬")
        List<ProductOfferSummary> offers
) {
}
