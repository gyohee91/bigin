package com.ghyinc.finance.domain.loan.external.coocon.dto;

import lombok.Builder;

@Builder
public record KbAppraisalResult(
        String resultCd,
        String resultMg,
        String totalCount
        //KB부동산 시세 정보
) {
}
