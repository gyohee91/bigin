package com.ghyinc.finance.domain.loan.enums;

import com.ghyinc.finance.global.common.ConnectionType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PartnerCode {
    KAKAO_BANK("카카오뱅크", false, ConnectionType.REST),
    TOSS_BANK("토스뱅크", false, ConnectionType.REST),
    KB_CAPITAL("KB캐피탈", true, ConnectionType.REST),
    K_BANK("K뱅크", true, ConnectionType.REST),
    SHINHAN_BANK("신한은행", true, ConnectionType.LEASE_LINE),
    LINE_BANK("라인뱅크", true, ConnectionType.REST)
    ;

    private final String partnerName;
    private final boolean standard;
    private final ConnectionType connectionType;
}
