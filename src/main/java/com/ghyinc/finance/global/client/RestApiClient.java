package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
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
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;

    private final Map<PartnerCode, RestClient> partnerRestClients;

    @Override
    public <T> T post(PartnerCode partnerCode, String path, Object request, Class<T> responseType) {
        // 금융사별 독립 Circuit Breaker 적용
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(partnerCode.name());
        Retry retry = retryRegistry.retry(partnerCode.name());
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(partnerCode.name());


        // Retry -> Circuit Breaker 순으로 실행 (재시도가 모두 실패해야 Circuit Breaker 실패로 기록)
        // RateLimiter: 금융사별 TPS 제한 (초과 시 RequestNotPermitted 즉시 반환)
        // CircuitBreaker: 실패율 기반 장애 격리
        // Retry: CircuitBreaker 실패 기록 후 재시도
        return RateLimiter.decorateSupplier(rateLimiter,
                        CircuitBreaker.decorateSupplier(circuitBreaker,
                                Retry.decorateSupplier(retry, () -> {
                                log.info("[{}] Circuit Breaker 상태: {}", partnerCode, circuitBreaker.getState());

                                return partnerRestClients.get(partnerCode)
                                        .post()
                                        .uri(path)
                                        .header("X-Partner-Code", partnerCode.name())
                                        .body(request)
                                        .retrieve()
                                        .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                                            throw new ExternalApiFailException("한도조회_ERROR", partnerCode + " 4xx 오류");
                                        })
                                        .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                                            throw new ExternalApiFailException("한도조회_ERROR", partnerCode + " 5xx 오류");
                                        })
                                        .body(responseType);
                                })))
                .get();
    }
}
