package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.KakaoRequest;
import com.ghyinc.finance.domain.notification.dto.KakaoResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.global.config.NotificationApiProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoNotificationSender extends AbstractNotificationSender {
    private final NotificationApiProperties notificationApiProperties;

    public static final String REQUEST_ID_KEY = "requestId";

    public KakaoNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Qualifier("kakaotalkRestClient") RestClient restClient,
            NotificationApiProperties notificationApiProperties
    ) {
        super(circuitBreakerRegistry, retryRegistry, restClient);
        this.notificationApiProperties = notificationApiProperties;
    }

    @Override
    protected ExternalApiResponse callApi(Notification notification) {
        KakaoRequest requestDto = KakaoRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        String path = notificationApiProperties.getConfig(this.getChannelType()).getPath();
        return post(path, requestDto, KakaoResponse.class, this::toCommonResponse);
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
