package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;

public record ProductNameDto(
        PartnerCode partnerCode,
        String productCode,
        String productName
) {
}
