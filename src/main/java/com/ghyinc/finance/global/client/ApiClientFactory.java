package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiClientFactory {
    private final RestApiClient restApiClient;

    public ApiClient getApiClient(PartnerCode partnerCode) {
        return switch (partnerCode.getConnectionType()) {
            case REST -> restApiClient;
            case LEASE_LINE -> null;
        };
    }
}
