package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

@Builder
@JsonDeserialize(builder = ProductCache.ProductCacheBuilder.class)
public record ProductCache(
        Long id,
        String productCode,
        String productName,
        LoanType loanType,
        PartnerCode partnerCode,
        boolean active
) {
    public static ProductCache from(Product product) {
        return ProductCache.builder()
                .id(product.getId())
                .productCode(product.getProductCode())
                .productName(product.getProductName())
                .loanType(product.getLoanType())
                .partnerCode(product.getPartner().getPartnerCode())
                .build();
    }
}
