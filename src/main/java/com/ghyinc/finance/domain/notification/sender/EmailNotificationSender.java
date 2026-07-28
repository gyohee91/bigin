package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.EmailRequest;
import com.ghyinc.finance.domain.notification.dto.EmailResponse;
import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.global.config.NotificationApiProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class EmailNotificationSender extends AbstractNotificationSender {
    private final NotificationApiProperties notificationApiProperties;

    public static final String REQUEST_ID_KEY = "requestId";

    public EmailNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            RestClient restClient,
            NotificationApiProperties notificationApiProperties
    ) {
        super(circuitBreakerRegistry, retryRegistry, restClient);
        this.notificationApiProperties = notificationApiProperties;
    }

    @Override
    protected ExternalApiResponse callApi(Notification notification) {
        EmailRequest requestDto = EmailRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        String path = notificationApiProperties.getConfig(ChannelType.EMAIL).getPath();
        return post(path, requestDto, EmailResponse.class, this::toCommonResponse);
    }

    @Override
    public ChannelType getChannelType() {
        return ChannelType.EMAIL;
    }

    private ExternalApiResponse toCommonResponse(EmailResponse response) {
        String requestId = MDC.get(REQUEST_ID_KEY);
        if(response != null && "SUCCESS".equals(response.getResultCode())) {
            return ExternalApiResponse.success(requestId, response.getResultCode(), response);
        }
        String resultCode = response != null ? response.getResultCode() : "UNKNOWN";
        return ExternalApiResponse.fail(requestId, resultCode, "error");
    }
}
