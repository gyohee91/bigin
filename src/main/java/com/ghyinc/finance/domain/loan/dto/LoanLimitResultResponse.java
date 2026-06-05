package com.ghyinc.finance.domain.loan.dto;

import lombok.Builder;

/**
 * 표준 Layout 금융사 한도결과 콜백 응답 DTO
 *
 * <p>{@link ResultResponse} 인터페이스의 표준 구현체로
 * 표준 Layout 금융사에 공통으로 사용하는 콜백 처리 결과 응답이다.
 * 비표준 금융사(카카오뱅크, 토스뱅크 등)는 전용 응답 DTO를 사용한다.</p>
 *
 * <h3>응답 전략</h3>
 * <p>금융사는 본 응답을 기반으로 콜백 재전송 여부를 결정한다.
 * {@code FAIL} 응답 시 금융사가 콜백을 재전송할 수 있으므로
 * 중복 수신 방지 로직({@code PartnerInquiryStatus.SUCCESS} 체크)이
 * {@link com.ghyinc.finance.domain.loan.service.LoanLimitResultService}에서 반드시 선행되어야 한다.</p>
 *
 * @param resultCode    처리결과 코드. {@code "SUCCESS"} 또는 {@code "FAIL"}
 * @param resultMessage 처리결과 메시지. 실패 시 사유 포함, 성공 시 {@code null}
 */
@Builder
public record LoanLimitResultResponse(
        String resultCode,
        String resultMessage
) implements ResultResponse {

    /**
     * 정상 처리 응답을 생성한다.
     *
     * <p>콜백 데이터가 정상적으로 저장된 경우 사용한다.
     * 금융사는 {@code SUCCESS} 응답 수신 시 콜백 재전송을 중단한다.</p>
     *
     * @return  성공 응답 ({@code resultCode = "SUCCESS", resultMessage = null})
     */
    public static LoanLimitResultResponse success() {
        return LoanLimitResultResponse.builder()
                .resultCode("SUCCESS")
                .build();
    }

    /**
     * 처리 실패 응답을 생성한다.
     *
     * <p>유효하지 않은 loReqtNo, 처리 불가 상태 등 오류 발생 시 사용한다.
     * 금융사는 {@code FAIL} 응답 수신 시 콜백을 재전송할 수 있으므로
     * 중복 수신에 대한 방어 로직이 호출부에서 선행되어야 한다.</p>
     *
     * @param resultMessage 실패 사유 (금융사 측 재전송 여부 판단에 활용)
     * @return  실패 응답 ({@code resultCode = "FAIL", resultMessage = 실패 사유})
     */
    public static LoanLimitResultResponse fail(String resultMessage) {
        return LoanLimitResultResponse.builder()
                .resultCode("FAIL")
                .resultMessage(resultMessage)
                .build();
    }
}
