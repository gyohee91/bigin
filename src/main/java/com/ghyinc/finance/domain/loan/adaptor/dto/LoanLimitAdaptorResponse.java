package com.ghyinc.finance.domain.loan.adaptor.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

/**
 * 금융사 한도조회 API 응답 공통 DTO
 *
 * <p>금융사별 {@link com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor#inquireLimit(PartnerCode, LoanLimitAdaptorRequest)}
 * 실행 후 반환되며, 성공·실패 여부와 응답 시간을 포함한다.
 * 금융사별 응답 포맷의 차이를 구현체에서 흡수하므로
 * {@link com.ghyinc.finance.domain.loan.service.LoanLimitSenderService}는
 * 단일 타입으로 결과를 집계할 수 있다.</p>
 *
 * @see com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor#inquireLimit(PartnerCode, LoanLimitAdaptorRequest)
 * @see com.ghyinc.finance.domain.loan.service.LoanLimitSenderService
 *
 * @param partnerCode   응답을 반환한 금융사 코드. 결과 집계 및 Result 상태 UPDATE에 사용
 * @param success       API 전송 성공 여부. false 시 콜백 수신 대기 없이 SEND_FAILED 처리
 * @param failReason    실패 사유. Circuit Breaker OPEN, 4xx/5xx 오류, 타임아웃 등
 * @param resTimeMs     API 전송 소요 시간 (ms). LoanLimitResult에 기록되며 성능 모니터링에 활용
 */
@Builder
public record LoanLimitAdaptorResponse(
        PartnerCode partnerCode,
        boolean success,
        String failReason,
        long resTimeMs
) {

    /**
     * API 전송 성공 응답을 생성한다.
     *
     * <p>금융사에 요청이 정상 접수된 경우 사용한다.
     * 실제 한도결과는 금융사가 콜백으로 별도 전달한다.</p>
     *
     * @param partnerCode   응답 금융사 코드
     * @param resTimeMs     API 전송 소요 시간 (ms)
     * @return  성공 응답 DTO ({@code success = true, failReason = null})
     */
    public static LoanLimitAdaptorResponse success(
            PartnerCode partnerCode,
            long resTimeMs
    ) {
        return LoanLimitAdaptorResponse.builder()
                .partnerCode(partnerCode)
                .success(true)
                .failReason(null)
                .resTimeMs(resTimeMs)
                .build();
    }

    /**
     * API 전송 실패 응답을 생성한다.
     *
     * <p>4xx/5xx 오류, 타임아웃, Circuit Breaker OPEN 등 전송에 실패한 경우 사용한다.
     * 예외를 전파하지 않고 실패 응답을 반환하여 Partial Failure 패턴을 지원한다.
     * 해당 금융사의 {@code LoanLimitResult}는 {@code SEND_FAILED}로 기록된다.</p>
     *
     * @param partnerCode   응답 금융사 코드
     * @param failReason    실패 사유 (로그 및 LoanLimitResult 저장에 사용)
     * @param resTimeMs     API 전송 소요 시간 (ms)
     * @return  실패 응답 DTO ({@code success = false})
     */
    public static LoanLimitAdaptorResponse fail(
            PartnerCode partnerCode,
            String failReason,
            long resTimeMs
    ) {
        return LoanLimitAdaptorResponse.builder()
                .partnerCode(partnerCode)
                .success(false)
                .failReason(failReason)
                .resTimeMs(resTimeMs)
                .build();
    }
}
