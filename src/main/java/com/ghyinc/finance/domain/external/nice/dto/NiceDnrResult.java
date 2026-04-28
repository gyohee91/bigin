package com.ghyinc.finance.domain.external.nice.dto;

import lombok.Builder;

/**
 * Nice DNR 조회 결과 DTO
 * @param resultCode        결과 코드
 * @param autoInfo          갑구 정보
 * @param autoSecondInfo    을구 정보
 */
@Builder
public record NiceDnrResult(
        String resultCode,
        AutoInfo autoInfo,  //자동자등록원부(갑)
        AutoSecondInfo autoSecondInfo   //자동자등록원부(을)
) {
}
