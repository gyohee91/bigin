package com.ghyinc.finance.domain.loan.dto;

/**
 * 한도조회 수신 진행 값. 진행률 규칙의 단일 출처
 * @param total
 * @param success
 */
public record InquiryProgress(
        int total,
        int success
) {
    /* 0 ~ 100 (소수점 버림) */
    public int rate() {
        return total <= 0 ? 0 : (int) (success * 100.0 / total);
    }

    public boolean allReceived() {
        return total > 0 && success >= total;
    }
}
