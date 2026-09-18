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
    LINE_BANK("라인뱅크", false, ConnectionType.REST),

    // 시중은행
    KB_BANK("KB국민은행", true, ConnectionType.REST),
    WOORI_BANK("우리은행", true, ConnectionType.REST),
    HANA_BANK("하나은행", true, ConnectionType.REST),
    NH_BANK("NH농협은행", true, ConnectionType.REST),
    IBK_BANK("IBK기업은행", true, ConnectionType.REST),
    SC_BANK("SC제일은행", true, ConnectionType.REST),
    CITI_BANK("한국씨티은행", true, ConnectionType.REST),

    // 지방은행
    DGB_BANK("DGB대구은행", true, ConnectionType.REST),
    BUSAN_BANK("부산은행", true, ConnectionType.REST),
    KYONGNAM_BANK("경남은행", true, ConnectionType.REST),
    GWANGJU_BANK("광주은행", true, ConnectionType.REST),
    JEONBUK_BANK("전북은행", true, ConnectionType.REST),
    JEJU_BANK("제주은행", true, ConnectionType.REST),

    // 캐피탈
    HYUNDAI_CAPITAL("현대캐피탈", true, ConnectionType.REST),
    SHINHAN_CAPITAL("신한캐피탈", true, ConnectionType.REST),
    LOTTE_CAPITAL("롯데캐피탈", true, ConnectionType.REST),
    HANA_CAPITAL("하나캐피탈", true, ConnectionType.REST),
    WOORI_FINANCIAL_CAPITAL("우리금융캐피탈", true, ConnectionType.REST),
    DGB_CAPITAL("DGB캐피탈", true, ConnectionType.REST),
    JB_WOORI_CAPITAL("JB우리캐피탈", true, ConnectionType.REST),
    BNK_CAPITAL("BNK캐피탈", true, ConnectionType.REST),
    IBK_CAPITAL("IBK캐피탈", true, ConnectionType.REST),
    MERITZ_CAPITAL("메리츠캐피탈", true, ConnectionType.REST),
    ORIX_CAPITAL("오릭스캐피탈", true, ConnectionType.REST),
    MIRAE_ASSET_CAPITAL("미래에셋캐피탈", true, ConnectionType.REST),
    ACUON_CAPITAL("애큐온캐피탈", true, ConnectionType.REST),
    KOREA_INVESTMENT_CAPITAL("한국투자캐피탈", true, ConnectionType.REST),
    DB_CAPITAL("DB캐피탈", true, ConnectionType.REST),

    // 저축은행
    SBI_SAVINGS_BANK("SBI저축은행", true, ConnectionType.REST),
    OK_SAVINGS_BANK("OK저축은행", true, ConnectionType.REST),
    WELCOME_SAVINGS_BANK("웰컴저축은행", true, ConnectionType.REST),
    PEPPER_SAVINGS_BANK("페퍼저축은행", true, ConnectionType.REST),
    ACUON_SAVINGS_BANK("애큐온저축은행", true, ConnectionType.REST),
    KOREA_INVESTMENT_SAVINGS_BANK("한국투자저축은행", true, ConnectionType.REST),
    DAISHIN_SAVINGS_BANK("대신저축은행", true, ConnectionType.REST),
    YUJIN_SAVINGS_BANK("유진저축은행", true, ConnectionType.REST),
    JT_CHINAE_SAVINGS_BANK("JT친애저축은행", true, ConnectionType.REST),
    MOA_SAVINGS_BANK("모아저축은행", true, ConnectionType.REST),

    // 카드사
    SHINHAN_CARD("신한카드", true, ConnectionType.REST),
    SAMSUNG_CARD("삼성카드", true, ConnectionType.REST),
    KB_CARD("KB국민카드", true, ConnectionType.REST),
    HYUNDAI_CARD("현대카드", true, ConnectionType.REST),
    LOTTE_CARD("롯데카드", true, ConnectionType.REST),
    WOORI_CARD("우리카드", true, ConnectionType.REST);

    private final String partnerName;
    private final boolean standard;
    private final ConnectionType connectionType;
}
