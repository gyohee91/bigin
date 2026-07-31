package com.ghyinc.finance.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExternalApiResponse {
    private String resultCode;
    private String resultMessage;
    private boolean success;
    private Object data;

    /**
     * 성공 응답
     *
     * @param resultCode    응답 코드 (성공: SUCCESS 등)
     * @param data          응답 메시지
     */
    public static ExternalApiResponse success(String resultCode, Object data) {
        return ExternalApiResponse.builder()
                .resultCode(resultCode)
                .resultMessage("SUCCESS")
                .success(true)
                .data(data)
                .build();
    }

    /**
     * 실패 응답 생성 (HTTP 200이지만 body가 실패인 경우
     * ExternalApiService가 ExternalApiFailException으로 변환하는 대상
     */
    public static ExternalApiResponse fail(String resultCode, String resultMessage) {
        return ExternalApiResponse.builder()
                .resultCode(resultCode)
                .resultMessage(resultMessage)
                .success(false)
                .build();
    }

    /**
     * Fallback 응답 생성 (Circuit Breaker OPEN 또는 Retry 소진 시)
     * 서비스 레이어에서 조회불가 처리 후 상위로 전달
     */
    public static ExternalApiResponse unavailable() {
        return ExternalApiResponse.builder()
                .resultCode("UNAVAILABLE")
                .resultMessage("외부 상품 일시적으로 사용 불가")
                .success(false)
                .build();
    }
}
