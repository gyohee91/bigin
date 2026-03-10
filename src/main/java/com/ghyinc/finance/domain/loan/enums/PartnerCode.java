package com.ghyinc.finance.domain.loan.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PartnerCode {
    KAKAO_BANK("카카오뱅크"),
    TOSS_BANK("토스뱅크"),
    KB_CAPITAL("KB캐피탈"),
    K_BANK("K뱅크"),
    SHINHAN_BANK("신한은행"),
    LINE_BANK("라인뱅크")
    ;

    private final String partnerName;
}
