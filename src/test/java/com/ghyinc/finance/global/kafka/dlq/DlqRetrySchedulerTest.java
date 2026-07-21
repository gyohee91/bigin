package com.ghyinc.finance.global.kafka.dlq;

import com.ghyinc.finance.global.kafka.dlq.entity.DlqEvent;
import com.ghyinc.finance.global.kafka.dlq.entity.DlqStatus;
import com.ghyinc.finance.global.kafka.dlq.repository.DlqEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private KafkaTemplate<String, String> kafkaTemplate;

    private DlqEvent buildDlqEvent(int retryCount) {
        DlqEvent event = DlqEvent.builder()
                .topic("notification.send")
                .dlqTopic("notification.send.DLT")
                .payload("{\"id\": 1}")
                .errorMessage("연결 오류")
                .errorType("java.net.ConnectionException")
                .kafkaOffset(100L)
                .kafkaPartition(0)
                .status(DlqStatus.PENDING)
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
    @DisplayName("재시도 성공 → RESOLVED 상태로 변경")
    void retry_kafkaSuccess_markAsResolved() {
        // given
        DlqEvent event = this.buildDlqEvent(0);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        SendResult<String, Object> sendResult = mock(SendResult.class);
        CompletableFuture<SendResult<String, Object>> future = CompletableFuture.completedFuture(sendResult);
        doReturn(future).when(kafkaTemplate).send(anyString(), any());

        // when
        dlqRetryScheduler.retry();

        // then - RESOLVED로 변경 후 save
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should(atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(e -> e.getStatus() == DlqStatus.RESOLVED);
    }


    @Test
    @DisplayName("최대 재시도 초과 → DEAD 상태로 변경")
    void retry_maxRetryExceeded_markAsDead() {
        // given - retryCount = 5 (최대 초과)
        DlqEvent event = this.buildDlqEvent(5);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        // when
        dlqRetryScheduler.retry();

        // then
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DlqStatus.DEAD);
        then(kafkaTemplate).should(never()).send(anyString(), any());
    }

    @Test
    @DisplayName("지수 백오프 - 1회 재시도 후 nextRetryAt 갱신")
    void retry_exponentialBackOff_setsCorrectNextRetryAt() {
        // given
        DlqEvent event = this.buildDlqEvent(0);
        given(dlqEventRepository.findRetryTarget(any(), any(), anyInt()))
                .willReturn(List.of(event));

        CompletableFuture future = new CompletableFuture();
        doReturn(future).when(kafkaTemplate).send(anyString(), any());

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