package com.ghyinc.finance.domain.notification.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ExternalApiResponse {
    private Long id;
    private String resultCode;
    private String resultMessage;
    private boolean success;
    private Object data;

    public static ExternalApiResponse success(Long id, String resultCode, Object data) {
        return ExternalApiResponse.builder()
                .id(id)
                .resultCode(resultCode)
                .resultMessage("SUCCESS")
                .success(true)
                .data(data)
                .build();
    }

    public static ExternalApiResponse fail(Long id, String resultCode, String resultMessage) {
        return ExternalApiResponse.builder()
                .id(id)
                .resultCode(resultCode)
                .resultMessage(resultMessage)
                .success(false)
                .build();
    }

    /**
     * Fallback 응답 생성 (Circuit Breaker OPEN 또는 Retry 소진 시)
     * 서비스 레이어에서 조회불가 처리 후 상위로 전달
     * @param id
     * @return
     */
    public static ExternalApiResponse unavailable(Long id) {
        return ExternalApiResponse.builder()
                .id(id)
                .resultCode("UNAVAILABLE")
                .resultMessage("외부 상품 일시적으로 사용 불가")
                .success(false)
                .build();
    }
}
