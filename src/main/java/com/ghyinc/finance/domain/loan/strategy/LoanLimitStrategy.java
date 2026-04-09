package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

import java.util.List;

/**
 * 대출 유형별 한도조회 전략 인터페이스
 *
 * 대출 종류마다 조회 가능한 은행, 유효성 검증,
 * Adaptor 요청 변환 방식이 다르므로 Strategy 패턴으로 분리
 *
 * [역할 분담]
 * Strategy: 어떤 은행에 조회할지, 어떻게 요청을 구성할지 결정
 * Adaptor: 해당 은행 API를 어떻게 호출할지 담당
 * Service: 전략 선택하고 어댑터 조율
 *
 */
public interface LoanLimitStrategy {
    /**
     * 이 전락이 처리하는 대출 유형
     */
    LoanType getLoanType();

    /**
     * 조회 가능 금융사 목록
     * - 대출 유형 별로 지원 금융사가 다를 수 있음.
     * @return
     */
    List<PartnerCode> getSupportedBanks();

    void validate(LoanLimitRequest request);

    ExternalDataContext fetchExternalData(LoanLimitRequest request);

    /**
     * 서비스 요청을 어댑터 요청으로 변환
     * - 대출 유형별 loanTypeCode, 필드 매핑 방식이 다름
     * @param request
     * @return
     */
    LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext);

    /**
     * 외부 API 조회 필요 여부
     * @return
     */
    boolean requiresExternalData();

    /**
     * 외부 API 실패 시 진행 가능한 금융사 필터링
     * @param activePartnerCodes
     * @param context
     * @return
     */
    default List<PartnerCode> filterAvailablePartners(List<PartnerCode> activePartnerCodes, ExternalDataContext context) {
        return activePartnerCodes;  // 기본: 전체 진행
    }

    /**
     * 어댑터 응답 후처리
     * - 대출 유형별 후처리 로직이 필요한 경우 오버라이드
     * - 기본 구현은 응답을 그대로 반환
     * @param response
     * @return
     */
    default LoanLimitAdaptorResponse postProcess(LoanLimitAdaptorResponse response) {
        return response;
    }
}
