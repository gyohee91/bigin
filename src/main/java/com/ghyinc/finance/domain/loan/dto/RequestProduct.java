package com.ghyinc.finance.domain.loan.dto;

import lombok.Builder;

@Builder
public record RequestProduct(
        String loReqtNo,
        String productCode
) {
}
