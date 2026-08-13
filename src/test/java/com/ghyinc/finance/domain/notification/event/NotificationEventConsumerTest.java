package com.ghyinc.finance.domain.notification.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.notification.service.NotificationSenderService;
import com.ghyinc.finance.global.exception.KafkaMessageDeserializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {
    @InjectMocks
    private NotificationEventConsumer notificationEventConsumer;

    @Mock
    private NotificationSenderService notificationSenderService;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("정상 payload이면 NotificationSenderService에 처리를 위임한다")
    void consume_delegatesToSenderService() throws JsonProcessingException {
        String payload = "{\"id\":1}";
        given(objectMapper.readValue(payload, NotificationEvent.class))
                .willReturn(NotificationEvent.builder().id(1L).build());

        notificationEventConsumer.consume(payload);

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
        assertThatThrownBy(() -> notificationEventConsumer.consume(payload))
                .isInstanceOf(KafkaMessageDeserializationException.class);
    }
}