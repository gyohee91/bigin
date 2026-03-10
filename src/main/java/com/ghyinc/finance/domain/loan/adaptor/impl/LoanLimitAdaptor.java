package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

/**
 * 외부 금융사 한도조회 API 어댑터 인터페이스
 * <p>
 * 각 은행별 API 명세가 상이하더라도 동일한 인터페이스로 호출 가능하도록 추상화
 * 구현체는 실제 외부 API와의 통신 및 요청/응답 변환을 담당한다
 *
 * <pre>
 * [흐름]
 * Service -> Adaptor Interface -> 은행별 구현체 -> 외부 API -> 공통 Response로 변환
 * </pre>
 */
public interface LoanLimitAdaptor {
    /**
     * 현재 어댑터가 지원하는 Partner code
     */
    boolean supports(PartnerCode partnerCode);

    /**
     * 외부 금융사 API 호출하여 대출 한도 조회
     *
     * @param request   공통 요청 DTO
     * @return  공통 응답 DTO (성공/실패 여부 포함)
     */
    LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest request);
}
