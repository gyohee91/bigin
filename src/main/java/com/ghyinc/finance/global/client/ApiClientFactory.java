package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiClientFactory {
    private final RestApiClient restApiClient;
    private final LeaseLineApiClient leaseLineApiClient;

    public ApiClient getApiClient(PartnerCode partnerCode) {
        return switch (partnerCode.getConnectionType()) {
            case REST -> restApiClient;             // REST-API
            case LEASE_LINE -> leaseLineApiClient;  // 전용선
            case SOAP -> null;                      // SOAP
        };
    }
}
