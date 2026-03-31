package com.ghyinc.finance.domain.loan.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 대출 유형
 */
@Getter
@RequiredArgsConstructor
public enum LoanType {
    PERSONAL_CREDIT("신용대출"),
    MORTGATE("주택담보대출"),
    BUSINESS("사업자대출"),
    AUTO("오토담보대출");

    private final String description;
}
