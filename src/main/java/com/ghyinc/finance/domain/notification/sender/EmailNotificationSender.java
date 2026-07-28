package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.EmailRequest;
import com.ghyinc.finance.domain.notification.dto.EmailResponse;
import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class EmailNotificationSender extends AbstractNotificationSender {
    private final RestTemplate restTemplate;

    public static final String REQUEST_ID_KEY = "requestId";

    @Value("${notification.sender.email.base-url}")
    private String url;

    public EmailNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            RestTemplate restTemplate
    ) {
        super(circuitBreakerRegistry, retryRegistry);
        this.restTemplate = restTemplate;
    }

    @Override
    protected ExternalApiResponse callApi(Notification notification) {
        EmailRequest requestDto = EmailRequest.builder()
                .recipient(notification.getRecipient())
                .title(notification.getTitle())
                .content(notification.getContent())
                .build();

        HttpHeaders headers = new HttpHeaders();
        HttpEntity<EmailRequest> httpEntity = new HttpEntity<>(requestDto, headers);
        ResponseEntity<EmailResponse> responseEntity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                httpEntity,
                EmailResponse.class
        );

        return this.toCommonResponse(responseEntity.getBody());
    }

    @Override
    public ChannelType getChannelType() {
        return null;
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
