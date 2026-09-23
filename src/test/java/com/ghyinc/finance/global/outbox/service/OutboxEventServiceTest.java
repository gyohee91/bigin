package com.ghyinc.finance.global.outbox.service;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidRequestException;
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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
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

    @Test
    @DisplayName("Notification aggregateType → notification.send 토픽 발행")
    void publishToKafka_notificationTopic() {
        // given
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("Notification")
                .aggregateId("1")
                .payload("{}")
                .build();

        given(kafkaTemplate.send(eq("notification.send"), anyString(), any()))
                .willReturn(mock(CompletableFuture.class));

        // when
        outboxEventService.publishToKafka(outboxEvent);

        // then
        then(kafkaTemplate).should().send(eq("notification.send"), anyString(), any());
    }

    @Test
    @DisplayName("알 수 없는 aggregateType → InvalidRequestException")
    void publishToKafka_unknownAggregateType_throwsException() {
        // given
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .aggregateType("Unknown")
                .aggregateId("1")
                .payload("{}")
                .build();

        // when & then
        assertThatThrownBy(() -> outboxEventService.publishToKafka(outboxEvent))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("알 수 없는 aggregateType: " + outboxEvent.getAggregateType());
    }

    @Test
    @DisplayName("publishToKafkaSync 성공 - true 반환 (배치 재시도가 결과를 동기 확인)")
    void publishToKafkaSync_success_returnsTrue() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();
        CompletableFuture<SendResult<String, String>> future =
                CompletableFuture.completedFuture(mock(SendResult.class));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);

        // when
        boolean result = outboxEventService.publishToKafkaSync(outboxEvent, Duration.ofSeconds(3));

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("publishToKafkaSync 타임아웃 - false 반환하고 예외를 밖으로 던지지 않는다")
    void publishToKafkaSync_timeout_returnsFalseWithoutThrowing() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();
        // 절대 완료되지 않는 Future -> get(timeout)이 TimeoutException을 던지는 상황 재현
        CompletableFuture<SendResult<String, String>> neverCompletes = new CompletableFuture<>();
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(neverCompletes);

        // when
        boolean result = outboxEventService.publishToKafkaSync(outboxEvent, Duration.ofMillis(100));

        // then - CompletableFuture 체인이 절대 예외로 끝나지 않아야
        // OutboxEventBatchPublisher의 supplyAsync().thenAccept()가 항상 실행된다
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("applyRetryResult 성공 - PUBLISHED로 반영")
    void applyRetryResult_success_marksPublished() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(outboxEvent));

        // when
        outboxEventService.applyRetryResult(1L, true);

        // then
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(outboxEvent.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("applyRetryResult 실패 - FAILED로 반영 (배치 재시도가 마지막 기회)")
    void applyRetryResult_failure_marksFailed() {
        // given
        OutboxEvent outboxEvent = this.buildPendingOutboxEvent();
        given(outboxEventRepository.findById(1L)).willReturn(Optional.of(outboxEvent));

        // when
        outboxEventService.applyRetryResult(1L, false);

        // then
        assertThat(outboxEvent.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(outboxEvent.getFailCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("applyRetryResult - 존재하지 않는 id는 조용히 스킵한다")
    void applyRetryResult_notFound_doesNothing() {
        // given
        given(outboxEventRepository.findById(999L)).willReturn(Optional.empty());

        // when & then - 예외 없이 조용히 반환
        outboxEventService.applyRetryResult(999L, true);
    }
}