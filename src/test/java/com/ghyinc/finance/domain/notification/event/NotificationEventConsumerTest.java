package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.dto.ExternalApiResponse;
import com.ghyinc.finance.domain.notification.entity.Notification;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.NotificationStatus;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.repository.NotificationRepository;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
import com.ghyinc.finance.global.exception.KafkaMessageDeserializationException;
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

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {
    @InjectMocks
    private NotificationEventConsumer notificationEventConsumer;

    @Mock
    private NotificationSenderService notificationSenderService;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ObjectMapper objectMapper;

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
    void consume_marksNotificationAsSuccess_whenSendSucceeds() throws JsonProcessingException {
        // given
        String payload = "{\"id\":1}";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willReturn(NotificationEvent.builder().id(1L).build());

        Notification notification = this.buildNotification();
        given(notificationRepository.findById(1L)).willReturn(Optional.of(notification));
        given(notificationSenderService.call(notification))
                .willReturn(ExternalApiResponse.success("req-1", "SUCCESS", null));

        // when
        notificationEventConsumer.consume(payload);

        // then
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SUCCESS);
        assertThat(notification.getResultCode()).isEqualTo("SUCCESS");
        assertThat(notification.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("발송 실패 응답이면 markAsFailed로 상태를 변경한다")
    void consume_marksNotificationAsFailed_whenSendFails() throws JsonProcessingException {
        // given
        String payload = "{\"id\":1}";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willReturn(NotificationEvent.builder().id(1L).build());

        Notification notification = this.buildNotification();
        given(notificationRepository.findById(1L)).willReturn(Optional.of(notification));
        given(notificationSenderService.call(notification))
                .willReturn(ExternalApiResponse.fail("req-1", "PARTNER_ERROR", "외부 API 실패"));

        // when
        notificationEventConsumer.consume(payload);

        // then
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getResultCode()).isEqualTo("PARTNER_ERROR");
        assertThat(notification.getSentAt()).isNull();
    }

    @Test
    @DisplayName("존재하지 않는 Notification id이면 예외가 발생한다")
    void consume_throwsException_whenNotificationNotFound() throws JsonProcessingException {
        String payload = "{\"id\":1}";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willReturn(NotificationEvent.builder().id(999L).build());
        given(notificationRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> notificationEventConsumer.consume(payload))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("페이로드 파싱 실패 시 KafkaMessageDeserializationException을 던져 DLQ로 보낸다")
    void consume_throwsKafkaMessageDeserializationException_whenPayloadParsingFails() throws JsonProcessingException {
        // given
        String payload = "invalid-json";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willThrow(new JsonMappingException(null, "파싱 실패"));

        // when & then
        assertThatThrownBy(() -> notificationEventConsumer.consume(payload))
                .isInstanceOf(KafkaMessageDeserializationException.class);
    }
}