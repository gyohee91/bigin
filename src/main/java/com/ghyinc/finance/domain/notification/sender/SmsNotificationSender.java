package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.dto.SmsRequest;
import com.ghyinc.finance.domain.notification.dto.SmsResponse;
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
public class SmsNotificationSender extends AbstractNotificationSender {
    private final RestTemplate restTemplate;

    public static final String REQUEST_ID_KEY = "requestId";

    @Value("${notification.sender.sms.base-url}")
    private String url;

    public SmsNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            RestTemplate restTemplate
    ) {
        super(circuitBreakerRegistry, retryRegistry);
        this.restTemplate = restTemplate;
    }

    @Override
    protected ExternalApiResponse callApi(Notification notification) {
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

        return this.toCommonResponse(responseEntity.getBody());
    }

    @Override
    public ChannelType getChannelType() {
        return ChannelType.SMS;
    }

    private ExternalApiResponse toCommonResponse(SmsResponse response) {
        String requestId = MDC.get(REQUEST_ID_KEY);
        if(response != null && "SUCCESS".equals(response.getResultCode())) {
            return ExternalApiResponse.success(requestId, response.getResultCode(), response);
        }
        String resultCode = response != null ? response.getResultCode() : "UNKNOWN";
        return ExternalApiResponse.fail(requestId, resultCode, "error");
    }
}
