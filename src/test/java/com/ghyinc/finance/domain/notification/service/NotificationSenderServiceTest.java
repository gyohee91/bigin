package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.NotificationStatus;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.notification.sender.NotificationSender;
import com.ghyinc.finance.domain.notification.sender.NotificationSenderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSenderServiceTest {
    @InjectMocks
    private NotificationSenderService notificationSenderService;

    @Mock
    private NotificationSender notificationSender;

    @Mock
    private NotificationSenderFactory notificationSenderFactory;

    @Mock
    private NotificationRepository notificationRepository;

    private Notification buildNotification() {
        return Notification.builder()
                .channelType(ChannelType.SMS)
                .sendType(SendType.IMMEDIATE)
                .recipient("윤교희")
                .title("한도조회 완료")
                .content("한도조회가 완료되었습니다.")
                .build();
    }

    @Test
    @DisplayName("발송 성공 응답이면 markAsSuccess로 상태를 변경한다")
    void sendAndUpdateResult_marksNotificationAsSuccess_whenSendSucceeds() {
        // given
        Notification notification = this.buildNotification();
        given(notificationRepository.findById(1L)).willReturn(Optional.of(notification));
        given(notificationSenderFactory.getSender(ChannelType.SMS))
                .willReturn(notificationSender);
        given(notificationSender.send(notification))
                .willReturn(ExternalApiResponse.success("SUCCESS", null));

        // when
        notificationSenderService.sendAndUpdateResult(1L);

        // then
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SUCCESS);
        assertThat(notification.getResultCode()).isEqualTo("SUCCESS");
        assertThat(notification.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("발송 실패 응답이면 markAsFailed로 상태를 변경한다")
    void sendAndUpdateResult_marksNotificationAsFailed_whenSendFails() {
        // given
        Notification notification = this.buildNotification();
        given(notificationRepository.findById(1L)).willReturn(Optional.of(notification));
        given(notificationSenderFactory.getSender(ChannelType.SMS))
                .willReturn(notificationSender);
        given(notificationSender.send(notification))
                .willReturn(ExternalApiResponse.fail("PARTNER_ERROR", "외부 API 실패"));

        // when
        notificationSenderService.sendAndUpdateResult(1L);

        // then
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getResultCode()).isEqualTo("PARTNER_ERROR");
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 Notification id이면 예외가 발생한다")
    void sendAndUpdateResult_throwsException_whenNotificationNotFound() {
        given(notificationRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> notificationSenderService.sendAndUpdateResult(999L))
                .isInstanceOf(NoSuchElementException.class);
    }

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