package com.ghyinc.finance.domain.external.coocon.dto;

import lombok.Builder;

@Builder
public record KbAppraisalResult(
        String resultCd,
        String resultMg,
        String totalCount,
        RespData respData
        // KB부동산 시세 정보
) {
}
