package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 금융사 한도결과 콜백 공통 요청 DTO
 *
 * <p>금융사별 {@link com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor#convert(JsonNode)}에서
 * 금융사별 응답 원문({@code JsonNode})을 본 DTO로 변환하여 반환한다.
 * {@link com.ghyinc.finance.domain.loan.service.LoanLimitResultService}는
 * 금융사별 포맷과 무관하게 단일 타입으로 한도결과를 처리한다.</p>
 *
 * <h3>1건 콜백 vs 복수건 콜백</h3>
 * <p>금융사는 1회 콜백에 복수 상품의 결과를 포함하여 전송할 수 있다.
 * {@code loanApplyResults}는 상품별 결과 목록을 담으며,
 * {@code loReqtNo + productCode} 조합으로 선저장된 {@code LoanLimitProductResult}와 매핑된다.</p>
 *
 * @see com.ghyinc.finance.domain.loan.service.LoanLimitResultService
 * @see com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor#convert(JsonNode)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanLimitResultRequest {
    @JsonProperty("loanApplyResults")

    /*  상품별 한도결과 목록. 1회 콜백에 복수 상품 결과가 포함될 수 있음. */
    private List<LoanApplyResult> loanApplyResults;

    /**
     * 상품 단위 한도 결과
     *
     * <p>{@code loReqtNo + productCode}가 선저장된 {@code LoanLimitProductResult}의
     * 조회 키로 사용된다. {@code loReqtNo}는 API 전송 시 채번된 업무 식별번호이며,
     * 금융사가 그대로 콜백에 포함하여 반환한다.</p>
     */
    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LoanApplyResult {
        private String loReqtNo;
        private String productCode;
        private LoanLimitResultCode resultCode;
        private Long amount;
        private Double interestRate;
    }
}
