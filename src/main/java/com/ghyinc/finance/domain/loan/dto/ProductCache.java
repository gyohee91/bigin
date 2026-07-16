package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductCache {

    private Long id;
    private String productCode;
    private String productName;
    private LoanType loanType;
    private PartnerCode partnerCode;
    private boolean active;

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