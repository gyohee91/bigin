package com.ghyinc.finance.domain.loan.dto;

import lombok.Builder;

@Builder
public record ExternalDataError(
        String code,
        String message
) {
}
