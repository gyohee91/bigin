package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.global.config.NotificationApiProperties;
import com.ghyinc.finance.global.exception.ExternalApiServerException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EmailNotificationSenderTest {
    private static final String BASE_URL = "http://email.test";
    private static final String PATH = "/send/email";

    private MockRestServiceServer mockServer;
    private EmailNotificationSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        mockServer = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        RetryRegistry retryRegistry = RetryRegistry.of(
                RetryConfig.custom()
                        .maxAttempts(3)
                        .waitDuration(Duration.ZERO)
                        .retryExceptions(ExternalApiServerException.class)
                        .build()
        );
        NotificationApiProperties.ChannelApiConfig config = new NotificationApiProperties.ChannelApiConfig();
        config.setPath(PATH);
        NotificationApiProperties properties = new NotificationApiProperties();
        properties.setChannels(Map.of(ChannelType.EMAIL, config));

        sender = new EmailNotificationSender(
                CircuitBreakerRegistry.ofDefaults(),
                retryRegistry,
                restClient,
                properties
        );
    }

    private Notification notification() {
        return Notification.builder()
                .channelType(ChannelType.EMAIL)
                .recipient("github@gmail.com")
                .title("제목")
                .content("내용")
                .build();
    }

    @Test
    @DisplayName("정상 응답이면 success로 변환한다")
    void convertsToSuccessWhenResponseOk() {
        mockServer.expect(requestTo(BASE_URL + PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.emailAddress").value("github@gmail.com"))
                .andRespond(withSuccess("{\"resultCode\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        ExternalApiResponse result = sender.send(this.notification());

        assertThat(result.isSuccess()).isTrue();
        mockServer.verify();
    }

    @Test
    @DisplayName("5xx 응답이 반복되면 재시도 후 UNAVAILABLE로 fallback한다")
    void fallsBackToUnavailableAfterServerErrorRetries() {
        mockServer.expect(times(3), requestTo(BASE_URL + PATH))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"error\":\"boom\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        ExternalApiResponse response = sender.send(this.notification());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getResultCode()).isEqualTo("UNAVAILABLE");
        mockServer.verify();   // 정확히 3회 호출했는지까지 검증
    }

    @Test
    @DisplayName("4xx 응답이면 재시도 없이 즉시 fallback한다")
    void fallsBackImmediatelyOnClientError() {
        mockServer.expect(times(1), requestTo(BASE_URL + PATH))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"bad request\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        ExternalApiResponse response = sender.send(this.notification());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getResultCode()).isEqualTo("UNAVAILABLE");
        mockServer.verify();   // 정확히 1회 호출했는지까지 검증
    }

    @Test
    @DisplayName("바디 resultCode가 SUCCESS가 아니면 재시도 없이 fallback한다")
    void fallsBackImmediatelyOnBusinessFailure() {
        mockServer.expect(times(1), requestTo(BASE_URL + PATH))
                .andRespond(withSuccess("{\"resultCode\":\"ERR_999\"}", MediaType.APPLICATION_JSON));

        ExternalApiResponse response = sender.send(notification());

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getResultCode()).isEqualTo("UNAVAILABLE");
        mockServer.verify();
    }
}