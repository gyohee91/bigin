package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.sender.NotificationSender;
import com.ghyinc.finance.domain.notification.sender.NotificationSenderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSenderServiceTest {
    @InjectMocks
    private NotificationSenderService notificationSenderService;

    @Mock
    private NotificationSender notificationSender;

    @Mock
    private NotificationSenderFactory notificationSenderFactory;

    @Test
    @DisplayName("채널 타입에 맞는 Sender에게 발송을 위임한다")
    void call() {
        Notification notification = Notification.builder()
                .channelType(ChannelType.SMS)
                .recipient("010-1234-5678")
                .build();

        ExternalApiResponse expected = ExternalApiResponse.success("SUCCESS", null);

        when(notificationSenderFactory.getSender(ChannelType.SMS)).thenReturn(notificationSender);
        when(notificationSender.send(notification)).thenReturn(expected);

        assertThat(notificationSenderService.call(notification)).isEqualTo(expected);
    }
}