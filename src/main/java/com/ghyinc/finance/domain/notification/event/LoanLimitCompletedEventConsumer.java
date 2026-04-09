package com.ghyinc.finance.domain.notification.event;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LoanLimitCompletedEventConsumer {
    private final NotificationService notificationService;

    @KafkaListener(
            topics = "loan-limit-completed",
            groupId = "notification-group"
    )
    public void consume(LoanLimitCompletedEvent event) {
        notificationService.sendNotification(
                NotificationSendRequest.builder()
                        .channelType(ChannelType.SMS)
                        .sendType(SendType.IMMEDIATE)
                        .recipient(event.getName())
                        .title("한도조회 완료")
                        .content(this.buildContent(event.getStatus()))
                        .build()
        );
    }

    private String buildContent(InquiryStatus status) {
        return switch (status) {
            case SUCCESS -> "한도조회가 완료되었습니다. 결과를 확인해보세요";
            case FAILED -> "한도조회 중 오류가 발생했습니다. 다시 시도해주세요.";
            default -> "한도조회 상태가 업데이트되었습니다";
        };
    }
}
