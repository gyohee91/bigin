package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * REST 방식
 */
@Component
@RequiredArgsConstructor
public class RestApiClient implements ApiClient {
    private final Map<PartnerCode, RestClient> partnerRestClients;

    @Override
    public <T> T post(PartnerCode partnerCode, String path, Object request, Class<T> responseType) {
        return partnerRestClients.get(partnerCode)
                .post()
                .uri(path)
                .body(request)
                .retrieve()
                .body(responseType);
    }
}
