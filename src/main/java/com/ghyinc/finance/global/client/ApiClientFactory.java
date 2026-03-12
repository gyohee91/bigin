package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ApiClientFactory {
    private final RestApiClient restApiClient;

    //yml에서 금융사별 통신 방식 설정
    private final PartnerApiProperties partnerApiProperties;

    public ApiClient getApiClient(PartnerCode partnerCode) {
        PartnerApiProperties.PartnerApiConfig config = partnerApiProperties.getConfig(partnerCode);

        return switch (config.getConnectionType()) {
            case REST -> restApiClient;
        };
    }
}
