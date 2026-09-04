package com.ghyinc.finance.domain.notification.service;

import com.ghyinc.finance.domain.notification.dto.NotificationBulkResponse;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

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

    @Test
    @DisplayName("sendBulk: 매칭되는 Member 전원에 대해 Notification 저장과 Outbox 적재")
    void sendBulk_savesNotificationAndEnqueuesOutbox_forAllMatchedMembers() {
        // given
        List<NotificationSendRequest> requests = List.of(
                NotificationSendRequest.builder()
                        .userId(1L)
                        .channelType(ChannelType.KAKAOTALK)
                        .sendType(SendType.IMMEDIATE)
                        .title("title")
                        .content("content")
                        .build(),
                NotificationSendRequest.builder()
                        .userId(2L)
                        .channelType(ChannelType.KAKAOTALK)
                        .sendType(SendType.IMMEDIATE)
                        .title("title")
                        .content("content")
                        .build()
        );

        Member member1 = Member.builder()
                .userId(1L)
                .name("Tom")
                .mobile("01012341234")
                .build();
        Member member2 = Member.builder()
                .userId(2L)
                .name("Json")
                .mobile("01012341233")
                .build();
        
        given(memberRepository.findAllById(List.of(1L, 2L))).willReturn(List.of(member1, member2));
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        NotificationBulkResponse result = notificationService.setBulk(requests);

        // then
        assertThat(result.notified()).isEqualTo(2L);
        assertThat(result.skipped()).isEqualTo(0);

        then(notificationRepository).should(times(2)).save(any(Notification.class));
        then(outboxEventWriter).should(times(2)).enqueue(
                eq("Notification"), any(), eq("NOTIFICATION_SEND"), any(NotificationEvent.class)
        );
    }

    @Test
    @DisplayName("sendBulk: 매칭되지 않는 Member는 스킵하고 나머지만 발송한다")
    void sendBulk_skipsUnmatchedMembers() {
        // given
        List<NotificationSendRequest> requests = List.of(
                NotificationSendRequest.builder()
                        .userId(1L)
                        .channelType(ChannelType.KAKAOTALK)
                        .sendType(SendType.IMMEDIATE)
                        .title("title")
                        .content("content")
                        .build(),
                NotificationSendRequest.builder()
                        .userId(999L)
                        .channelType(ChannelType.KAKAOTALK)
                        .sendType(SendType.IMMEDIATE)
                        .title("title")
                        .content("content")
                        .build()
        );

        Member member = Member.builder()
                .userId(1L)
                .name("Tom")
                .mobile("01012341234")
                .build();

        // 999L은 조회 결과에 포함되지 않음 (존재하지 않는 회원)
        given(memberRepository.findAllById(List.of(1L, 999L))).willReturn(List.of(member));
        given(notificationRepository.save(any(Notification.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        // when
        NotificationBulkResponse result = notificationService.setBulk(requests);

        // then
        assertThat(result.notified()).isEqualTo(1L);
        assertThat(result.skipped()).isEqualTo(1L);
        then(notificationRepository).should(times(1)).save(any(Notification.class));
    }

    @Test
    @DisplayName("sendBulk: 빈 리스트를 넘기면 아무 처리도 하지 않는다")
    void sendBulk_emptyRequests_doesNothing() {
        // when
        NotificationBulkResponse result = notificationService.setBulk(List.of());

        // then
        assertThat(result.notified()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
        then(notificationRepository).shouldHaveNoInteractions();
        then(outboxEventWriter).shouldHaveNoInteractions();
    }
}