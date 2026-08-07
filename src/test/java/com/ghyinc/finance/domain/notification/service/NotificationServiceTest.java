package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.dto.NotificationSendResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.event.NotificationEvent;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import com.ghyinc.finance.global.outbox.service.OutboxEventWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @InjectMocks
    private NotificationService notificationService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    private Member member() {
        return Member.builder()
                .userId(1L)
                .name("젠슨황")
                .mobile("01012341234")
                .email("github@gmail.com")
                .build();
    }

    private NotificationSendRequest buildRequest() {
        return NotificationSendRequest.builder()
                .userId(1L)
                .channelType(ChannelType.SMS)
                .sendType(SendType.IMMEDIATE)
                .recipient("젠슨황")
                .title("한도조회 완료")
                .content("한도조회가 완료되었습니다.")
                .build();
    }

    @Test
    @DisplayName("Notification을 저장하고 Outbox에 NOTIFICATION_SEND 이벤트를 적재한다")
    void sendNotification_savesNotificationAndEnqueuesOutboxEvent() {
        // given
        NotificationSendRequest request = this.buildRequest();

        given(memberRepository.findById(1L)).willReturn(Optional.of(this.member()));
        // 실제 JPA(IDENTITY) 저장 동작 재현: save() 시 같은 인스턴스에 id를 채워 반환
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> {
                    Notification saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 1L);
                    return saved;
                });

        // when
        NotificationSendResponse response = notificationService.sendNotification(request);

        // then
        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(notificationCaptor.capture());

        Notification savedNotification = notificationCaptor.getValue();
        assertThat(savedNotification.getChannelType()).isEqualTo(ChannelType.SMS);
        assertThat(savedNotification.getSendType()).isEqualTo(SendType.IMMEDIATE);
        assertThat(savedNotification.getRecipient()).isEqualTo("01012341234");
        assertThat(savedNotification.getTitle()).isEqualTo("한도조회 완료");
        assertThat(savedNotification.getContent()).isEqualTo("한도조회가 완료되었습니다.");

        ArgumentCaptor<String> aggregateIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<NotificationEvent> payloadCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        then(outboxEventWriter).should().enqueue(
                eq("Notification"),
                aggregateIdCaptor.capture(),
                eq("NOTIFICATION_SEND"),
                payloadCaptor.capture()
        );
        assertThat(aggregateIdCaptor.getValue()).isEqualTo("1");
        assertThat(payloadCaptor.getValue().getId()).isEqualTo(1L);
        assertThat(payloadCaptor.getValue().getChannelType()).isEqualTo(ChannelType.SMS);
        assertThat(payloadCaptor.getValue().getRecipient()).isEqualTo("01012341234");

        assertThat(response.getNotificationId()).isEqualTo(1L);
        assertThat(response.getChannelType()).isEqualTo(ChannelType.SMS);
    }

    @Test
    @DisplayName("SCHEDULED 발송 요청이면 scheduledAt이 설정된다")
    void sendNotification_scheduledType_setScheduledAt() {
        // given
        NotificationSendRequest request = NotificationSendRequest.builder()
                .userId(1L)
                .channelType(ChannelType.EMAIL)
                .sendType(SendType.SCHEDULED)
                .recipient("test@test.com")
                .title("title")
                .content("content")
                .build();
        given(memberRepository.findById(1L)).willReturn(Optional.of(this.member()));
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        notificationService.sendNotification(request);

        // then
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getScheduledAt()).isNotNull();
    }

    @Test
    @DisplayName("IMMEDIATE 발송 요청이면 scheduledAt은 null이다")
    void sendNotification_immediateType_scheduledAtIsNull() {
        // given
        NotificationSendRequest request = this.buildRequest();
        given(memberRepository.findById(1L)).willReturn(Optional.of(this.member()));
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        notificationService.sendNotification(request);

        // then
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        then(notificationRepository).should().save(captor.capture());
        assertThat(captor.getValue().getScheduledAt()).isNull();
    }
}