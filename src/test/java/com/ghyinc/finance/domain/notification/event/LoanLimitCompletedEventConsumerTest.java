package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.notification.dto.NotificationSendRequest;
import com.ghyinc.finance.domain.notification.enums.ChannelType;
import com.ghyinc.finance.domain.notification.enums.SendType;
import com.ghyinc.finance.domain.notification.service.NotificationService;
import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.global.exception.KafkaMessageDeserializationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoanLimitCompletedEventConsumerTest {
    @InjectMocks
    private LoanLimitCompletedEventConsumer loanLimitCompletedEventConsumer;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ObjectMapper objectMapper;

    private LoanLimitCompletedEvent buildEvent(InquiryStatus status) {
        return LoanLimitCompletedEvent.builder()
                .inquiryNo("LL20260410A3F2C891")
                .userId(1L)
                .name("빈살만")
                .status(status)
                .build();
    }

    private ConsumerRecord<String, String> buildRecord(String payload) {
        return new ConsumerRecord<>("loan-limit-completed", 0, 0L, "LL20260410A3F2C891", payload);
    }

    @Test
    @DisplayName("SUCCESS 상태이면 성공 안내 문구로 SMS 발송을 요청한다")
    void consume_marksNotificationAsSuccess_whenSendSucceeds() throws JsonProcessingException {
        // given
        String payload = "{}";
        given(objectMapper.readValue(payload, LoanLimitCompletedEvent.class))
                .willReturn(this.buildEvent(InquiryStatus.SUCCESS));

        // when
        loanLimitCompletedEventConsumer.consume(payload, this.buildRecord(payload));

        // then
        ArgumentCaptor<NotificationSendRequest> captor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        then(notificationService).should().sendNotification(captor.capture());

        NotificationSendRequest request = captor.getValue();
        assertThat(request.getChannelType()).isEqualTo(ChannelType.SMS);
        assertThat(request.getSendType()).isEqualTo(SendType.IMMEDIATE);
        assertThat(request.getRecipient()).isEqualTo("빈살만");
        assertThat(request.getContent()).contains("완료되었습니다");
    }

    @Test
    @DisplayName("FAILED 상태면 실패 안내 문구로 SMS 발송을 요청한다")
    void consume_marksNotificationAsFailed_whenSendFails() throws JsonProcessingException {
        // given
        String payload = "{}";
        given(objectMapper.readValue(payload, LoanLimitCompletedEvent.class))
                .willReturn(this.buildEvent(InquiryStatus.FAILED));

        // when
        loanLimitCompletedEventConsumer.consume(payload, this.buildRecord(payload));

        // then
        ArgumentCaptor<NotificationSendRequest> captor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        then(notificationService).should().sendNotification(captor.capture());
        assertThat(captor.getValue().getContent()).contains("오류가 발생했습니다");
    }

    @Test
    @DisplayName("그 외 상태(PARTIAL_SUCCESS 등)면 기본 안내 문구로 SMS 발송을 요청한다")
    void consume_partialSuccess_sendDefaultContent() throws JsonProcessingException {
        // given
        String payload = "{}";
        given(objectMapper.readValue(payload, LoanLimitCompletedEvent.class))
                .willReturn(this.buildEvent(InquiryStatus.PARTIAL_SUCCESS));

        // when
        loanLimitCompletedEventConsumer.consume(payload, this.buildRecord(payload));

        // then
        ArgumentCaptor<NotificationSendRequest> captor = ArgumentCaptor.forClass(NotificationSendRequest.class);
        then(notificationService).should().sendNotification(captor.capture());
        assertThat(captor.getValue().getContent()).contains("상태가 업데이트되었습니다");
    }

    @Test
    @DisplayName("페이로드 파싱 실패 시 KafkaMessageDeserializationException을 던져 DLQ로 보낸다")
    void consume_throwsKafkaMessageDeserializationException_whenPayloadParsingFails() throws JsonProcessingException {
        // given
        String payload = "invalid-json";
        given(objectMapper.readValue(payload, LoanLimitCompletedEvent.class))
                .willThrow(new JsonMappingException(null, "파싱 실패"));

        // when & then
        assertThatThrownBy(() -> loanLimitCompletedEventConsumer.consume(payload, this.buildRecord(payload)))
                .isInstanceOf(KafkaMessageDeserializationException.class);
    }
}
