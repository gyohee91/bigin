package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.stereotype.Component;

/**
 * 전용선 방식
 */
@Component
public class LeaseLineApiClient implements ApiClient {
    @Override
    public <T> T post(PartnerCode partnerCode, String path, Object request, Class<T> responseType) {

        return null;
    }
}
