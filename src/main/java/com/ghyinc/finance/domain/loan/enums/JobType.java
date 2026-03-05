package com.ghyinc.finance.domain.loan.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum JobType {
    EMPLOYEE("직장인"),
    PUBLIC_SERVANT("공무원"),
    HOUSEKEEPER("주부"),
    PERSONAL_ETC("기타"),
    SOLE_PROPRIETORSHIP("개인사업자");

    private final String name;
}
