package com.ghyinc.finance.domain.loan.adaptor.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

/**
 * 금융사별 한도결과 콜백 어댑터 인터페이스
 *
 * <p>금융사마다 콜백 요청 포맷이 상이하므로 금융사별 구현체가 자사 포맷을
 * 공통 DTO{@link LoanLimitResultRequest}로 변환하고 응답을 구성한다.
 * 호출부({@link com.ghyinc.finance.domain.loan.service.LoanLimitResultService})는
 * 금융사 포맷을 알 필요 없이 동일한 인터페이스만 처리한다.</p>
 *
 * <h3>처리 흐름</h3>
 * <pre>
 *     금융사 → POST /api/loan/limit/callbank
 *      → LoanLimitResultService
 *          → LoanLimitResultAdaptorFactory.getAdaptor(partnerCode)
 *          → LoanLimitResultAdaptor.convert(reqBody)   ← 금융사 포맷 → 공통 DTO
 *          → LoanLimitResultService.process()          ← 비즈니스 처리
 *          → LoanLimitResultAdaptor.buildResponse()    ← 금융사별 응답 구성
 *      → 금융사에 처리 결과 반환
 * </pre>
 *
 * <h3>구현체 분류</h3>
 * <ul>
 *     <li>{@code CommonLoanLimitResultAdaptor}: 표준 콜백 포맷 금융사 공통 처리</li>
 *     <li>{@code KakaobankLoanLimitResultAdaptor}: 카카오뱅크 전용 콜백 포맷 변환</li>
 *     <li>{@code LinebankLoanLimitResultAdaptor}: 라인뱅크 전용 콜백 포맷 변환</li>
 * </ul>
 *
 * @see LoanLimitResultAdaptorFactory
 * @see com.ghyinc.finance.domain.loan.service.LoanLimitResultService
 */
public interface LoanLimitResultAdaptor {

    /**
     * 이 어댑터가 주어진 금융사 코드의 콜백을 처리할 수 있는지 여부를 반환한다.
     *
     * <p>{@link LoanLimitResultAdaptorFactory}가 {@code supports()}를 순차 평가하여
     * 적합한 어댑터를 선택한다. 표준 포맷 금융사는 {@code CommonLoanLimitResultAdaptor}가,
     * 비표준 금융사는 전용 구현체가 처리한다.</p>
     *
     * @param partnerCode   금융사 코드
     * @return  이 어댑터가 해당 금융사 콜백을 처리할 수 있으면 {@code true}
     */
    boolean supports(PartnerCode partnerCode);

    /**
     * 금융사별 콜백 요청 원문을 공통 DTO로 변환한다.
     *
     * <p>금융사마다 필드명, 중첩 구조, 인코딩 방식이 다르므로 구현체가
     * 자사 포맷을 {@link LoanLimitResultRequest}로 변환하여 반환한다.
     * 변환 실패 시 예외를 발생시켜 호출부에서 오류 응답을 구성한다.</p>
     *
     * @param body  금융사 콜백 요청 원문 ({@code JsonNode} 형태로 수신)
     * @return  공통 한도 결과 DTO
     * @throws com.ghyinc.finance.global.exception.ExternalApiFailException 포맷 변환 실패 시
     */
    LoanLimitResultRequest convert(JsonNode body);

    /**
     * 금융사에 반환할 처리 결과 응답을 구성한다.
     *
     * <p>처리 성공/실패 여부와 메시지를 금융사별 응답 포맷으로 변환한다.
     * 금융사는 본 응답을 기반으로 콜백 재전송 여부를 결정하므로
     * 응답 코드와 메시지는 금융사 API 명세를 준수해야 한다.</p>
     *
     * @param success       처리 성공 여부
     * @param resultMessage 처리 결과 메시지
     * @return  금융사별 응답 포맷으로 구성된 결과 DTO
     */
    ResultResponse buildResponse(boolean success, String resultMessage);
}
