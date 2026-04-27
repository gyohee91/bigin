package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitCompletedEventConsumer {
    private final NotificationService notificationService;

    private final ObjectMapper objectMapper;

    private static final String REQUEST_ID_KEY = "requestId";

    @KafkaListener(
            topics = "loan-limit-completed",
            groupId = "notification-group"
    )
    public void consume(String payload) {

        try {
            LoanLimitCompletedEvent event = objectMapper.readValue(payload, LoanLimitCompletedEvent.class);

            String requestId = Optional.ofNullable(event.getRequestId())
                    .orElse(UUID.randomUUID().toString());

            MDC.put(REQUEST_ID_KEY, requestId);
            log.info("[Consumer] 한도조회 완료 이벤트 수신. inquiryNo={}", event.getInquiryNo());

            notificationService.sendNotification(
                    NotificationSendRequest.builder()
                            .channelType(ChannelType.SMS)
                            .sendType(SendType.IMMEDIATE)
                            .recipient(event.getName())
                            .title("한도조회 완료")
                            .content(this.buildContent(event.getStatus()))
                            .build()
            );

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String buildContent(InquiryStatus status) {
        return switch (status) {
            case SUCCESS -> "한도조회가 완료되었습니다. 결과를 확인해보세요";
            case FAILED -> "한도조회 중 오류가 발생했습니다. 다시 시도해주세요.";
            default -> "한도조회 상태가 업데이트되었습니다";
        };
    }
}
