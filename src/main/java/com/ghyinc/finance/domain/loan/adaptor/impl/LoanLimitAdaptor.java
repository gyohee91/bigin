package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

/**
 * 금융사별 한도조회 API 어댑터 인터페이스
 *
 * <p>금융사마다 API 명세, 요청/응답 포맷, 통신 방식(REST/전용선)이 상이하더라도
 * 호출부({@link com.ghyinc.finance.domain.loan.service.LoanLimitSenderService})가
 * 동일한 인터페이스로 호출할 수 있도록 추상화한다.
 * 구현체 금융사별 요청/응답 포맷 변환과 외부 API 통신을 담당한다.</p>
 *
 * <h3>처리 흐름</h3>
 * <pre>
 *     LoanLimitSenderService
 *      → LoanLimitAdaptorFactory.getAdaptor(partnerCode)
 *      → LoanLimitAdaptor.inquireLimit()       ← 본 인터페이스
 *          → 공통 DTO → 금융사별 요청 포맷 변환
 *          → ApiClient.post() (REST / 전용선)
 *          → 금융사별 응답 포맷 → 공통 DTO 변환
 *      → LoanLimitAdaptorResponse 반환
 * </pre>
 *
 * <h3>구현체 분류</h3>
 * <ul>
 *     <li>{@code CommonLoanLimitAdaptor}: 표준 Layout 금융사 공통 처리
 *          ({@code supports()}: {@code partnerCode.isStandard() == true})</li>
 *     <li>{@code KakaobankLoanLimitAdaptor}: 카카오뱅크 전용 요청/응답 포맷</li>
 *     <li>{@code TossbankLoanLimitAdaptor}: 토스뱅크 전용 요청/응답 포맷</li>
 * </ul>
 *
 * <h3>Resilience4j 적용</h3>
 * <p>구현체 내부 {@link com.ghyinc.finance.global.client.ApiClient}에서
 * Circuit Breaker + Retry가 적용된다. Circuit Breaker OPEN 시
 * {@code CallNotPermittedException}이 발생하며 구현체의 {@code catch} 절에서
 * Fallback 응답({@code LoanLimitAdaptorResponse.fail()})을 반환한다.</p>
 *
 * @see com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory
 * @see com.ghyinc.finance.global.client.ApiClient
 */
public interface LoanLimitAdaptor {

    /**
     * 이 어댑터가 주어진 금융사 코드를 지원하는지 여부를 반환한다.
     *
     * <p>{@link com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory}가 {@code supports()}를 순차 평가하여
     * 적합한 어댑터를 선택한다. 표준 Layout 금융사는 {@code CommonLoanLimitAdaptor}가
     * 처리하고, 비표준 금융사는 전용 구현체가 처리한다.</p>
     *
     * @param partnerCode   금융사 코드
     * @return  이 어댑터가 해당 금융사를 처리할 수 있으면 {@code true}
     */
    boolean supports(PartnerCode partnerCode);

    /**
     * 금융사 한도조회 API를 호출하고 공통 응답 DTO로 반환한다.
     *
     * <p>공통 요청 DTO({@link LoanLimitAdaptorRequest})를 금융사별 요청 포맷으로 변환하여
     * API를 호출한다. 응답은 금융사별 포맷에서 공통 DTO{@link LoanLimitAdaptorResponse}로
     * 변환하여 반환한다.</p>
     *
     * <h3>실패 처리</h3>
     * <p>API 호출 실패(4xx, 5xx, timeout) 또는 Circuit Breaker OPEN 시
     * 예외를 전파하지 않고 {@code LoanLimitAdaptorResponse.fail()}을 반환한다.
     * 이를 통해 특정 금융사 장애가 전체 한도조회를 중단시키지 않는다.</p>
     *
     * @param partnerCode   금융사 코드 (Circuit Breaker 인스턴스 식별, 로깅에 사용)
     * @param request       금융사 전송용 공통 요청 DTO
     * @return  공통 응답 DTO (성공/실패 여부, 응답 시간 포함)
     */
    LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest request);
}
