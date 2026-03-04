package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.SmsRequest;
import com.ghyinc.finance.domain.notification.dto.SmsResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalApiClient {
    private final RestTemplate restTemplate;
    private final WebClient webClient;

    public static final String REQUEST_ID_KEY = "requestId";

    public ExternalApiResponse requestRestTemplate(Notification notification, String url) {
        SmsRequest requestDto = SmsRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<SmsRequest> httpEntity = new HttpEntity<>(requestDto, headers);

        ResponseEntity<SmsResponse> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                httpEntity,
                SmsResponse.class
        );

        SmsResponse result = responseEntity.getBody();

        return this.toCommonResponse(notification, result);
    }

    public ExternalApiResponse requestWebClient(Notification notification, String url) {
        SmsRequest requestDto = SmsRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        SmsResponse result = webClient.post()
                .uri(url)
                .header("X-Request-Id", MDC.get(REQUEST_ID_KEY))
                .bodyValue(requestDto)
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new IOException("5xx 오류: " + body))
                )
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new ExternalApiFailException("CLIENT_ERROR", "4xx 오류: " + body))
                )
                .bodyToMono(SmsResponse.class)
                .block();

        return this.toCommonResponse(notification, result);
    }

    public Mono<ExternalApiResponse> requestMono(Notification notification, String url) {
        SmsRequest requestDto = SmsRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        return webClient.post()
                .uri(url)
                .header("X-Request-Id", MDC.get(REQUEST_ID_KEY))
                .bodyValue(requestDto)
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new IOException("5xx: " + body))
                )
                .onStatus(HttpStatusCode::is4xxClientError, response ->
                        response.bodyToMono(String.class)
                                .map(body -> new ExternalApiFailException("CLIENT_ERROR", "4xx: " + body))
                )
                .bodyToMono(SmsResponse.class)
                .map(response -> this.toCommonResponse(notification, response))
                .onErrorResume(Exception.class, ex -> {
                    log.warn("호출 실패 - requestId: {}, error: {}", MDC.get(REQUEST_ID_KEY), ex.getMessage());
                    return Mono.just(ExternalApiResponse.fail(MDC.get(REQUEST_ID_KEY), "UNAVAILABLE", ex.getMessage()));
                });
    }

    private ExternalApiResponse toCommonResponse(Notification notification, SmsResponse response) {
        if("SUCCESS".equals(response.getResultCode())) {
            return ExternalApiResponse.success(MDC.get("requestId"), response.getResultCode(), response);
        }
        else {
            return ExternalApiResponse.fail(MDC.get("requestId"), response.getResultCode(), "error");
            //throw new ExternalApiFailException(response.getResultCode(), "error");
        }
    }

}
