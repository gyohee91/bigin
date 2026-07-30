package com.ghyinc.finance.global.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.global.exception.OutboxEventSerializationException;
import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class OutboxEventWriterTest {
    @InjectMocks
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    private record TestPayload(String value) {}

    @Test
    @DisplayName("enqueue 호출 시 Outbox 이벤트를 PENDING 상태로 저장한다")
    void enqueue_savesOutboxEventAsPending() throws JsonProcessingException {
        // given
        given(objectMapper.writeValueAsString(any())).willReturn("{\"value\":\"test\"}");

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("LoanLimitInquiry")
                .aggregateId("LL20260410A3F2C891")
                .eventType("LOAN_LIMIT_COMPLETED")
                .payload("{\"value\":\"test\"}")
                .status(OutboxStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(outboxEvent, "id", 1L);
        given(outboxEventRepository.save(any(OutboxEvent.class))).willReturn(outboxEvent);

        // when
        OutboxEvent result = outboxEventWriter.enqueue(
                "LoanLimitInquiry", "LL20260410A3F2C891", "LOAN_LIMIT_COMPLETED",
                new TestPayload("test")
        );

        // then
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should().save(captor.capture());

        OutboxEvent captured = captor.getValue();
        assertThat(captured.getAggregateType()).isEqualTo("LoanLimitInquiry");
        assertThat(captured.getAggregateId()).isEqualTo("LL20260410A3F2C891");
        assertThat(captured.getEventType()).isEqualTo("LOAN_LIMIT_COMPLETED");
        assertThat(captured.getPayload()).isEqualTo("{\"value\":\"test\"}");
        assertThat(captured.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(result).isEqualTo(outboxEvent);
    }

    @Test
    @DisplayName("enqueue 호출 시 저장된 OutboxEvent의 id로 OutboxCreatedEvent를 발행한다")
    void enqueue_publishesOutboxCreatedEventWithSavedId() throws JsonProcessingException {
        // given
        given(objectMapper.writeValueAsString(any())).willReturn("{}");

        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("Notification")
                .aggregateId("1")
                .eventType("LOAN_LIMIT_COMPLETED")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(outboxEvent, "id", 42L);
        given(outboxEventRepository.save(any(OutboxEvent.class))).willReturn(outboxEvent);

        // when
        outboxEventWriter.enqueue("Notification", "1", "NOTIFICATION_SEND", new TestPayload("x"));

        // then
        ArgumentCaptor<OutboxCreatedEvent> eventCaptor = ArgumentCaptor.forClass(OutboxCreatedEvent.class);
        then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().id()).isEqualTo(42L);
    }

    @Test
    @DisplayName("payload 직렬화 실패 시 OutboxEventSerializationException을 던지고 저장/발행하지 않는다")
    void enqueue_throwsSerializationException_whenPayloadSerializationFails() throws JsonProcessingException {
        // given
        given(objectMapper.writeValueAsString(any()))
                .willThrow(new JsonMappingException(null, "직렬화 실패"));

        // when & then
        assertThatThrownBy(() ->
                outboxEventWriter.enqueue("Notification", "1", "NOTIFICATION_SEND", new TestPayload("x"))
        ).isInstanceOf(OutboxEventSerializationException.class);

        then(outboxEventRepository).should(never()).save(any());
        then(applicationEventPublisher).should(never()).publishEvent(any());
    }
}