package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.KakaoRequest;
import com.ghyinc.finance.domain.notification.dto.KakaoResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class KakaoNotificationSender extends AbstractNotificationSender {
    private final RestTemplate restTemplate;

    public static final String REQUEST_ID_KEY = "requestId";

    @Value("${notification.sender.kakaotalk.base-url}")
    private String url;

    public KakaoNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            RestTemplate restTemplate
    ) {
        super(circuitBreakerRegistry, retryRegistry);
        this.restTemplate = restTemplate;
    }

    @Override
    protected ExternalApiResponse callApi(Notification notification) {
        KakaoRequest requestDto = KakaoRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<KakaoRequest> httpEntity = new HttpEntity<>(requestDto, headers);
        ResponseEntity<KakaoResponse> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                httpEntity,
                KakaoResponse.class
        );

        return this.toCommonResponse(responseEntity.getBody());
    }

    @Override
    public ChannelType getChannelType() {
        return ChannelType.KAKAOTALK;
    }

    private ExternalApiResponse toCommonResponse(KakaoResponse response) {
        String requestId = MDC.get(REQUEST_ID_KEY);
        if(response != null && "SUCCESS".equals(response.getResultCode())) {
            return ExternalApiResponse.success(requestId, response.getResultCode(), response);
        }
        String resultCode = response != null ? response.getResultCode() : "UNKNOWN";
        return ExternalApiResponse.fail(requestId, resultCode, "error");
    }
}
