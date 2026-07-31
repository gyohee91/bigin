package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.KakaoRequest;
import com.ghyinc.finance.domain.notification.dto.KakaoResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.global.config.NotificationApiProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoNotificationSender extends AbstractNotificationSender {
    private final RestClient restClient;
    private final NotificationApiProperties notificationApiProperties;

    public KakaoNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Qualifier("kakaotalkRestClient") RestClient restClient,
            NotificationApiProperties notificationApiProperties
    ) {
        super(circuitBreakerRegistry, retryRegistry);
        this.restClient = restClient;
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
        return post(restClient, path, requestDto, KakaoResponse.class, this::toCommonResponse);
    }

    @Override
    public ChannelType getChannelType() {
        return ChannelType.KAKAOTALK;
    }

    private ExternalApiResponse toCommonResponse(KakaoResponse response) {
        if(response != null && "SUCCESS".equals(response.getResultCode())) {
            return ExternalApiResponse.success(response.getResultCode(), response);
        }
        String resultCode = response != null ? response.getResultCode() : "UNKNOWN";
        return ExternalApiResponse.fail(resultCode, "error");
    }
}
