package com.ghyinc.finance.global.kafka.dlq;

import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DlqRetrySchedulerTest {
    @InjectMocks
    private DlqRetryScheduler dlqRetryScheduler;

    @Mock
    private DlqEventRepository dlqEventRepository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private DlqEvent buildDlqEvent(int retryCount) {
        DlqEvent event = DlqEvent.builder()
                .topic("notification.send")
                .dlqTopic("notification.send.DLT")
                .payload("{\"id\": 1}")
                .errorMessage("연결 오류")
                .errorType("java.net.ConnectException")
                .kafkaOffset(100L)
                .kafkaPartition(0)
                .status(DlqStatus.PENDING)
                .nextRetryAt(LocalDateTime.now().minusMinutes(1))
                .build();
        ReflectionTestUtils.setField(event, "id", 1L);
        ReflectionTestUtils.setField(event, "retryCount", retryCount);
        return event;
    }

    @Test
    @DisplayName("재시도 대상 없으면 Kafka 발행 없음")
    void retry_noTargets_nothingHappens() {
        // given
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(Collections.emptyList());

        // when
        dlqRetryScheduler.retry();

        // then
        then(kafkaTemplate).should(never()).send(anyString(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("재시도 성공 → RESOLVED 상태로 변경 (JPA dirty checking, 명시적 save 없음)")
    void retry_kafkaSuccess_markAsResolved() {
        // given
        DlqEvent event = buildDlqEvent(0);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        SendResult<String, Object> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, Object>> future =
                CompletableFuture.completedFuture(sendResult);
        doReturn(future).when(kafkaTemplate).send(anyString(), any());

        // when
        dlqRetryScheduler.retry();

        // then
        assertThat(event.getStatus()).isEqualTo(DlqStatus.RESOLVED);

        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kafka 재발행 실패 → RETRYING 상태 유지 (다음 주기에 재시도)")
    void retry_kafkaFails_staysRetrying() {
        // given
        DlqEvent event = buildDlqEvent(2);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture<SendResult<String, Object>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable"));
        doReturn(failedFuture).when(kafkaTemplate).send(anyString(), any());

        // when
        dlqRetryScheduler.retry();

        // then - RETRYING 유지, retryCount 증가 (다음 nextRetryAt 이후 재처리)
        assertThat(event.getStatus()).isEqualTo(DlqStatus.RETRYING);
        assertThat(event.getRetryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("최대 재시도 초과 → DEAD 상태로 변경, Kafka 발행 없음")
    void retry_maxRetryExceeded_markAsDead() {
        // given - retryCount = 5 (최대 초과)
        DlqEvent event = buildDlqEvent(5);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        // when
        dlqRetryScheduler.retry();

        // then
        assertThat(event.getStatus()).isEqualTo(DlqStatus.DEAD);
        assertThat(event.getErrorMessage()).contains("DEAD:");
        then(kafkaTemplate).should(never()).send(anyString(), any());
    }

    @Test
    @DisplayName("지수 백오프 - Kafka 실패 시에도 nextRetryAt이 2분 후로 갱신")
    void retry_exponentialBackOff_setsCorrectNextRetryAt() {
        // given
        DlqEvent event = buildDlqEvent(0);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        // 즉시 실패 → .get()이 ExecutionException 발생, 테스트 hang 없음
        CompletableFuture<SendResult<String, Object>> failedFuture =
                CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable"));
        doReturn(failedFuture).when(kafkaTemplate).send(anyString(), any());

        LocalDateTime before = LocalDateTime.now();

        // when
        dlqRetryScheduler.retry();

        // then - retryCount=1 → nextRetryAt = now + 2^1분 = 2분 후
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getNextRetryAt())
                .isAfter(before.plusMinutes(1))
                .isBefore(before.plusMinutes(3));
    }
}