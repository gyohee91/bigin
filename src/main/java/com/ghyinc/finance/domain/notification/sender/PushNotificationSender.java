package com.ghyinc.finance.domain.notification.sender;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.global.exception.ExternalApiClientException;
import com.ghyinc.finance.global.exception.ExternalApiServerException;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PushNotificationSender extends AbstractNotificationSender {
    private final ObjectProvider<FirebaseMessaging> firebaseMessagingProvider;

    public PushNotificationSender(
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            ObjectProvider<FirebaseMessaging> firebaseMessagingProvider
    ) {
        super(circuitBreakerRegistry, retryRegistry);
        this.firebaseMessagingProvider = firebaseMessagingProvider;
    }

    @Override
    protected ExternalApiResponse callApi(Notification notification) {
        FirebaseMessaging firebaseMessaging = firebaseMessagingProvider.getObject();

        Message message = Message.builder()
                .setToken(notification.getRecipient())
                .setNotification(
                        com.google.firebase.messaging.Notification.builder()
                                .setTitle(notification.getTitle())
                                .setBody(notification.getContent())
                                .build()
                )
                .build();

        try {
            String messageId = firebaseMessaging.send(message);
            return ExternalApiResponse.success("SUCCESS", messageId);
        } catch (FirebaseMessagingException e) {
            throw this.toApiException(e);
        }
    }

    private RuntimeException toApiException(FirebaseMessagingException e) {
        MessagingErrorCode code = e.getMessagingErrorCode();

        // 우리 쪽 요청이 잘못된 경우 -> 재시도 무의미 (4xx 상당)
        if (code == MessagingErrorCode.INVALID_ARGUMENT
                || code == MessagingErrorCode.UNREGISTERED
                || code == MessagingErrorCode.SENDER_ID_MISMATCH) {
            return new ExternalApiClientException(
                    code.name(), "FCM 요청 오류: " + e.getMessage());
        }

        // FCM 서버/네트워크/쿼터 문제 -> 재시도 대상 (5xx 상당)
        return new ExternalApiServerException(
                code.name(), "FCM 서버 오류: " + e.getMessage());
    }

    @Override
    public ChannelType getChannelType() {
        return ChannelType.PUSH;
    }
}
