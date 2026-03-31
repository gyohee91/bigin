package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.external.coocon.dto.KbAppraisalResult;
import com.ghyinc.finance.domain.loan.external.nice.dto.NiceDnrResult;
import lombok.Builder;

/**
 * 외부 API 조회 결과 컨텍스트
 * @param niceDnrResult
 * @param kbAppraisalResult
 */
@Builder
public record ExternalDataContext(
        NiceDnrResult niceDnrResult,        // 오토담보 - Nice DNR
        KbAppraisalResult kbAppraisalResult // 주담대   - KB부동산 시세
        // 상품 Type에 대한 외부 API 결과 데이터
) {
    // 빈 컨텍스트 (외부 조회 불필요한 대출 유형)
    public static ExternalDataContext empty() {
        return ExternalDataContext.builder().build();
    }
}
