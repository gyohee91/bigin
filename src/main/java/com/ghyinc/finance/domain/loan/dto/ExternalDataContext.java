package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.external.coocon.dto.KbAppraisalResult;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrResult;
import lombok.Builder;

import java.util.HashMap;
import java.util.Map;

/**
 * 외부 API 조회 결과 컨텍스트
 * @param niceDnrResult
 * @param kbAppraisalResult
 */
@Builder
public record ExternalDataContext(
        NiceDnrResult niceDnrResult,            // 오토담보 - Nice DNR
        KbAppraisalResult kbAppraisalResult,    // 주담대   - KB부동산 시세
        Map<String, ExternalDataError> errors   // 실패한 외부 조회 오류 정보
        // 상품 Type에 대한 외부 API 결과 데이터
) {
    // 빈 컨텍스트 (외부 조회 불필요한 대출 유형)
    public static ExternalDataContext empty() {
        return ExternalDataContext.builder()
                .errors(new HashMap<>())
                .build();
    }

    public boolean hasNiceDnrError() {
        return errors.containsKey("NICE_DNR");
    }

    public boolean hasKbAppraisalError() {
        return errors.containsKey("KB_APPRAISAL");
    }
}
