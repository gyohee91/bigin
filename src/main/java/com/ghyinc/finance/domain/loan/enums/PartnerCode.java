package com.ghyinc.finance.domain.loan.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PartnerCode {
    KAKAO_BANK("카카오뱅크"),
    TOSS_BANK("토스뱅크"),
    KB_CAPITAL("KB캐피탈");

    private final String partnerName;
}
