package com.ghyinc.finance.domain.external.nice.dto;

import lombok.Builder;

/**
 * Nice DNR 조회 요청 DTO
 * @param apiKey
 * @param loginId
 * @param kindOf
 * @param ownerNm   차주명
 * @param vhrNo     차량번호
 */
@Builder
public record NiceDnrRequest(
        String apiKey,
        String loginId,
        String kindOf,
        String ownerNm,
        String vhrNo
) {}
