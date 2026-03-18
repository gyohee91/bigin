package com.ghyinc.finance.domain.loan.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PartnerCode {
    KAKAO_BANK("카카오뱅크", false),
    TOSS_BANK("토스뱅크", false),
    KB_CAPITAL("KB캐피탈", true),
    K_BANK("K뱅크", true),
    SHINHAN_BANK("신한은행", true),
    LINE_BANK("라인뱅크", true)
    ;

    private final String partnerName;
    private final boolean standard;
}
