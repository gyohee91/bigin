package com.ghyinc.finance.domain.loan.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Objects;

/**
 * 한도조회 결과 코드
 */
@RequiredArgsConstructor
public enum LoanLimitResultCode {
    SUCCESS("00", "정상"),
    LIMIT_DENIED("11", "한도 부결"),
    DUPLICATE_REQUEST("21", "중복 신청"),
    INVALID_PRODUCT("22", "유효하지 않은 상품"),
    PARTNER_SYSTEM_ERROR("91", "금융사 시스템 오류"),
    TIMEOUT("92", "timeout"),
    UNKNOWN_ERROR("99", "알 수 없는 오류");

    private final String code;
    private final String description;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    @JsonCreator
    public static LoanLimitResultCode from(String resultCode) {
        for(LoanLimitResultCode code : LoanLimitResultCode.values()) {
            if(Objects.equals(code.code, resultCode)) {
                return code;
            }
        }

        return UNKNOWN_ERROR;
    }
}
