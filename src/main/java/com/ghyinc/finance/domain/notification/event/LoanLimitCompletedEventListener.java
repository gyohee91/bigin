package com.ghyinc.finance.domain.notification.event;

import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.domain.loan.event.LoanLimitCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class LoanLimitCompletedEventListener {
    private final NotificationService notificationService;

    //한도결과가 정상적으로 저장된 경우에만 알림 발송함
    //@EventListener만 쓰면 한도조회가 롤백되더라도 알림이 발송됨
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handle(LoanLimitCompletedEvent event) {
        notificationService.sendNotification(
                NotificationSendRequest.builder()
                        .userId(event.getUserId())
                        .channelType(ChannelType.SMS)
                        .sendType(SendType.IMMEDIATE)
                        .recipient(event.getLoReqtNo())
                        .title("한도조회 완료")
                        .content(".....")
                        .build()
        );
    }
}
