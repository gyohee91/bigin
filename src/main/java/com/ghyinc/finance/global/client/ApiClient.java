package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;

/**
 * 통신 방식 추상화
 */
public interface ApiClient {
    <T> T post(PartnerCode partnerCode, String path, Object request, Class<T> responseType);
}
