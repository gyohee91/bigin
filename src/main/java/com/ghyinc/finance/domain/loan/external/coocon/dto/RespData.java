package com.ghyinc.finance.domain.loan.external.coocon.dto;

import lombok.Builder;

@Builder
public record RespData(
        String region1Code,
        String region1Name,
        String region2Code,
        String region2Name,
        String region3Code,
        String region3Name,
        String ldongCode
) {
}
