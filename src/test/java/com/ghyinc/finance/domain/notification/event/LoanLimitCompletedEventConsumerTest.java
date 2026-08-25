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
import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class LoanLimitCompletedEventConsumerTest {
    @InjectMocks
    private LoanLimitCompletedEventConsumer loanLimitCompletedEventConsumer;

    @Mock
    private NotificationService notificationService;

    @Mock
    private DlqEventRepository dlqEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    private LoanLimitCompletedEvent buildEvent(InquiryStatus status) {
        return LoanLimitCompletedEvent.builder()
                .inquiryNo("LL20260410A3F2C891")
                .userId(1L)
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
        assertThat(request.getUserId()).isEqualTo(1L);
        assertThat(request.getChannelType()).isEqualTo(ChannelType.SMS);
        assertThat(request.getSendType()).isEqualTo(SendType.IMMEDIATE);
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

    @Test
    @DisplayName("handleDlt: 재시도 토픽을 모두 소진한 메시지를 DlqEvent(DEAD)로 기록한다")
    void handleDlt_savesDlqEventAsDead() {
        // given
        given(dlqEventRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        loanLimitCompletedEventConsumer.handleDlt(
                "{\"inquiryNo\":\"LL20260410A3F2C891\"}",
                this.buildRecord("{}"),
                "java.lang.IllegalArgumentException",
                "Member not found. id=null"
        );

        // then
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());

        DlqEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DlqStatus.DEAD);
        assertThat(saved.getTopic()).isEqualTo("loan-limit-completed");
        assertThat(saved.getErrorType()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(saved.getErrorMessage()).isEqualTo("Member not found. id=null");
    }

    @Test
    @DisplayName("handleDlt: DlqEvent 저장 자체가 실패해도 예외를 던지지 않는다 (FAIL_ON_ERROR 무한 재처리 방지)")
    void handleDlt_neverThrows_evenWhenSaveFails() {
        // given
        given(dlqEventRepository.save(any())).willThrow(new RuntimeException("DB 연결 실패"));

        // when & then
        assertThatCode(() -> loanLimitCompletedEventConsumer.handleDlt(
                "{}", this.buildRecord("{}"), "java.lang.RuntimeException", "DB 연결 실패"
        )).doesNotThrowAnyException();
    }
}
