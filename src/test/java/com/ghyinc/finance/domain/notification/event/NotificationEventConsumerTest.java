package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {
    @InjectMocks
    private NotificationEventConsumer notificationEventConsumer;

    @Mock
    private NotificationSenderService notificationSenderService;

    @Mock
    private DlqEventRepository dlqEventRepository;

    @Mock
    private ObjectMapper objectMapper;

    private ConsumerRecord<String, String> buildRecord(String payload) {
        return new ConsumerRecord<>("notification.send", 0, 0L, "1", payload);
    }

    @Test
    @DisplayName("정상 payload이면 NotificationSenderService에 처리를 위임한다")
    void consume_delegatesToSenderService() throws JsonProcessingException {
        String payload = "{\"id\":1}";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willReturn(NotificationEvent.builder().id(1L).build());

        notificationEventConsumer.consume(payload, this.buildRecord(payload));

        verify(notificationSenderService).sendAndUpdateResult(1L);
    }

    @Test
    @DisplayName("페이로드 파싱 실패 시 KafkaMessageDeserializationException을 던져 DLQ로 보낸다")
    void consume_throwsKafkaMessageDeserializationException_whenPayloadParsingFails() throws JsonProcessingException {
        // given
        String payload = "invalid-json";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willThrow(new JsonMappingException(null, "파싱 실패"));

        // when & then
        assertThatThrownBy(() -> notificationEventConsumer.consume(payload, this.buildRecord(payload)))
                .isInstanceOf(KafkaMessageDeserializationException.class);
    }

    @Test
    @DisplayName("handleDlt: 재시도 토픽을 모두 소진한 메시지를 DlqEvent(DEAD)로 기록한다")
    void handleDlt_savesDlqEventAsDead() {
        // given
        given(dlqEventRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        notificationEventConsumer.handleDlt(
                "{\"id\":1}",
                this.buildRecord("{\"id\":1}"),
                "java.lang.IllegalArgumentException",
                "Notification not found. id=1"
        );

        // then
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());

        DlqEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DlqStatus.DEAD);
        assertThat(saved.getTopic()).isEqualTo("notification.send");
        assertThat(saved.getErrorType()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(saved.getErrorMessage()).isEqualTo("Notification not found. id=1");
    }

    @Test
    @DisplayName("handleDlt: DlqEvent 저장 자체가 실패해도 예외를 던지지 않는다 (FAIL_ON_ERROR 무한 재처리 방지)")
    void handleDlt_neverThrows_evenWhenSaveFails() {
        // given
        given(dlqEventRepository.save(any())).willThrow(new RuntimeException("DB 연결 실패"));

        // when & then
        assertThatCode(() -> notificationEventConsumer.handleDlt(
                "{}", this.buildRecord("{}"), "java.lang.RuntimeException", "DB 연결 실패"
        )).doesNotThrowAnyException();
    }
}
