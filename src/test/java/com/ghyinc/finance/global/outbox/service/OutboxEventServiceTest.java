package com.ghyinc.finance.global.outbox.service;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceTest {
    @InjectMocks
    private OutboxEventService outboxEventService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxEvent buildPendingOutboxEvent() {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("LoanLimitInquiry")
                .aggregateId("LL20260410A3F2C891")
                .eventType("LOAN_LIMIT_COMPLETED")
                .payload("{\"inquiryNo\":\"LL20260410A3F2C891\"}")
                .status(OutboxStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(event, "id", 1L);
        return event;
    }

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("publishAfterCommit - OutboxEvent 조회 후 Kafka 발행")
    void publishAfterCommit_fetchesOutboxAndPublishes() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();
        given(outboxEventRepository.findById(1L))
                .willReturn(Optional.of(outboxEvent));

        CompletableFuture<SendResult<String, String>> future =
                CompletableFuture.completedFuture(mock(SendResult.class));
        given(kafkaTemplate.send(any(), any(), any())).willReturn(future);

        // when
        outboxEventService.publishAfterCommit(new OutboxCreatedEvent(1L));

        // then
        then(kafkaTemplate).should().send(
                eq("loan-limit-completed"),
                eq("LL20260410A3F2C891"),
                any()
        );
    }

    @Test
    @DisplayName("publishToKafka 성공 - OutboxEvent PUBLISHED UPDATE")
    void publishToKafka_success_markAsPublished() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();

        CompletableFuture<SendResult<String, String>> future =
                CompletableFuture.completedFuture(mock(SendResult.class));
        given(kafkaTemplate.send(any(), any(), any())).willReturn(future);

        // when
        outboxEventService.publishToKafka(outboxEvent);

        // then
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("publishAfterCommit 실패 - OutboxEvent PENDING 유지")
    void publishAfterCommit_failure_keepPending() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();

        CompletableFuture<SendResult<String, String>> failureFuture =
                new CompletableFuture<>();
        failureFuture.completeExceptionally(
                new RuntimeException("Kafka 브로커 장애"));
        given(kafkaTemplate.send(any(), any(), any()))
                .willReturn(failureFuture);     // whenComplete의 ex로 전달

        // when
        outboxEventService.publishToKafka(outboxEvent);

        // then - PENDING 유지 (배치가 재시도)
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
    }

}