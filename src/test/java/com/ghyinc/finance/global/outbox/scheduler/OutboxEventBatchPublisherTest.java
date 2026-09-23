package com.ghyinc.finance.global.outbox.scheduler;

import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import com.ghyinc.finance.global.outbox.service.OutboxEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * {@code publishToKafkaSync()}가 fire-and-forget이 아니라 결과를 동기적으로 확정한
 * 뒤에만 {@code applyRetryResult()}를 호출하는지 검증한다.
 *
 * <p>(2026-09 발견/수정) 예전 {@code publishToKafka()}는 비동기 콜백만 걸고 즉시
 * 리턴해서, 이 스케줄러가 결과를 기다리지 않고 다음 틱(1분 후)에 같은 PENDING 건을
 * 또 재시도 대상으로 집을 수 있었다 - 장애가 스케줄 주기보다 길어지면 같은 이벤트에
 * 대해 여러 {@code send()}가 동시에 in-flight 상태가 되어 Kafka 토픽에 중복 발행될
 * 위험이 있었다. 지금은 {@code get(timeout)}으로 결과를 확정 지은 뒤에만 상태를
 * 반영하므로, 한 틱 안에서 발행-확정이 끝난다.</p>
 *
 * <p>실제 스레드 풀({@code outboxRetryExecutor}) 대신 즉시 실행 Executor를 사용해
 * 비동기 타이밍에 좌우되지 않고 결정론적으로 검증한다.</p>
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventBatchPublisherTest {

    private OutboxEventBatchPublisher batchPublisher;

    @Mock
    private OutboxEventService outboxEventService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    // CompletableFuture.supplyAsync()가 테스트 스레드에서 즉시 실행되도록 - 실제 스레드풀 대체
    private final Executor immediateExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        batchPublisher = new OutboxEventBatchPublisher(
                outboxEventService, outboxEventRepository, immediateExecutor);
    }

    private OutboxEvent buildPendingEvent(long id) {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType("LoanLimitInquiry")
                .aggregateId("LL" + id)
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(event, "id", id);
        return event;
    }

    @Test
    @DisplayName("재시도 대상 각각에 대해 발행 결과를 확정한 뒤 applyRetryResult를 호출한다")
    void retryPendingEvents_appliesResultPerEvent() {
        // given
        OutboxEvent success = this.buildPendingEvent(1L);
        OutboxEvent failure = this.buildPendingEvent(2L);
        given(outboxEventRepository.findRetryTargets(
                eq(OutboxStatus.PENDING), any(LocalDateTime.class), eq(100)))
                .willReturn(List.of(success, failure));
        given(outboxEventService.publishToKafkaSync(eq(success), any(Duration.class)))
                .willReturn(true);
        given(outboxEventService.publishToKafkaSync(eq(failure), any(Duration.class)))
                .willReturn(false);

        // when
        batchPublisher.retryPendingEvents();

        // then - 각 이벤트는 자신의 발행 결과와 정확히 매칭되어 반영돼야 한다
        // (다른 이벤트의 결과를 잘못 적용하면 중복/오반영 버그를 놓치게 된다)
        then(outboxEventService).should().applyRetryResult(1L, true);
        then(outboxEventService).should().applyRetryResult(2L, false);
    }

    @Test
    @DisplayName("재시도 대상이 없으면 발행/반영 로직을 전혀 타지 않는다")
    void retryPendingEvents_noTargets_doesNothing() {
        // given
        given(outboxEventRepository.findRetryTargets(any(), any(), anyInt()))
                .willReturn(List.of());

        // when
        batchPublisher.retryPendingEvents();

        // then
        then(outboxEventService).should(never()).publishToKafkaSync(any(), any());
        then(outboxEventService).should(never()).applyRetryResult(any(), anyBoolean());
    }
}
