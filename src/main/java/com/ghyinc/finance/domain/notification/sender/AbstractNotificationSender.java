package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.global.exception.ExternalApiClientException;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import com.ghyinc.finance.global.exception.ExternalApiServerException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 채널별 CircuitBreaker/Retry 실행 로직 + HTTP 호출 배선을 공통화 공통화
 * 채널 구현체(SMS/EMAIL/KAKAOTALK)는 요청 DTO 조립 후 post()를 호출하는 callApi()만 구현하면 됨.
 * CB/Retry 인스턴스명은 ChannelType.name()과 1:1 매핑 (application.yaml resilience4j.instances 참고)
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbstractNotificationSender implements NotificationSender {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    protected abstract ExternalApiResponse callApi(Notification notification);

    @Override
    public final ExternalApiResponse send(Notification notification) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(this.getChannelType().name());
        Retry retry = retryRegistry.retry(this.getChannelType().name());

        Supplier<ExternalApiResponse> apiCall = () -> {
            ExternalApiResponse response = this.callApi(notification);

            if(!response.isSuccess()) {
                throw new ExternalApiFailException(
                        response.getResultCode(),
                        "외부 API 실패 - CODE: " + response.getResultCode()
                );
            }

            return response;
        };

        return Decorators.ofSupplier(apiCall)
                .withCircuitBreaker(circuitBreaker)
                .withRetry(retry)
                .withFallback(ex -> this.fallback(notification, ex))
                .decorate()
                .get();
    }

    /**
     * 채널 공통 HTTP POST 배선.
     * - 5xx / 연결 실패·타임아웃 -> ExternalApiServerException (Retry 대상 + CB 실패 집계)
     * - 4xx -> ExternalApiClientException (재시도 안 함, CB 실패 집계 제외 - 우리 쪽 요청 문제이므로)
     */
    protected final <Req, Res> ExternalApiResponse post(
            RestClient restClient,
            String path,
            Req requestDto,
            Class<Res> responseType,
            Function<Res, ExternalApiResponse> converter
    ) {
        try {
            Res result = restClient.post()
                    .uri(path)
                    .body(requestDto)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new ExternalApiClientException(
                                String.valueOf(res.getStatusCode().value()),
                                this.getChannelType() + " 4xx 응답: " + path
                        );
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ExternalApiServerException(
                                String.valueOf(res.getStatusCode().value()),
                                this.getChannelType() + " 5xx 응답: " + path
                        );
                    })
                    .body(responseType);

            return converter.apply(result);
        } catch (RestClientException e) {
            // 연결 실패, 타임아웃 등(ResourceAccessException 포함) -> 인프라성 오류로 취급
            throw new ExternalApiServerException("IO_ERROR", "외부 API 호출 실패: " + e.getMessage());
        }
    }

    private ExternalApiResponse fallback(Notification notification, Throwable ex) {
        if (ex instanceof CallNotPermittedException) {
            log.warn("[{}][{}]Circuit Breaker OPEN - 요청 차단됨", this.getChannelType(), notification.getId());
        } else if (ex instanceof ExternalApiServerException serverEx) {
            log.warn("[{}][{}] 재시도 소진 - 서버/네트워크 오류. resultCode: {}",
                    this.getChannelType(), notification.getId(), serverEx.getMessage());
        } else if (ex instanceof ExternalApiClientException clientEx) {
            log.warn("[{}][{}] 4xx 응답 - 재시도 없이 실패 처리. resultCode: {}",
                    this.getChannelType(), notification.getId(), clientEx.getMessage());
        } else if (ex instanceof ExternalApiFailException apiEx) {
            log.warn("[{}][{}] 재시도 소진 - body 실패 응답. resultCode: {}",
                    this.getChannelType(), notification.getId(), apiEx.getMessage());
        } else {
            log.warn("[{}][{}] 알 수 없는 오류. error: {}",
                    this.getChannelType(), notification.getId(), ex.getMessage());
        }

        return ExternalApiResponse.unavailable();
    }
}
