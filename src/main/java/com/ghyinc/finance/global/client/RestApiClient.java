package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * REST 방식
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestApiClient implements ApiClient {
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    private final Map<PartnerCode, RestClient> partnerRestClients;

    @Override
    public <T> T post(PartnerCode partnerCode, String path, Object request, Class<T> responseType) {
        //금융사별 독립 Circuit Breaker 적용
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(partnerCode.name());

        return circuitBreaker.executeSupplier(() -> {
            log.info("[{}] Circuit Breaker 상태: {}", partnerCode, circuitBreaker.getState());

            return partnerRestClients.get(partnerCode)
                    .post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ExternalApiFailException("한도조회_ERROR", partnerCode + " 4xx 오류");
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ExternalApiFailException("한도조회_ERROR", partnerCode + " 5xx 오류");
                    })
                    .body(responseType);
        });
    }
}
