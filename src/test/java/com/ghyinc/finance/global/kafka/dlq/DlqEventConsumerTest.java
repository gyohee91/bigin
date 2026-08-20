package com.ghyinc.finance.global.kafka.dlq;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class DlqEventConsumerTest {
    @InjectMocks
    private DlqEventConsumer dlqEventConsumer;

    @Mock
    private PoisonPillClassifier classifier;

    @Mock
    private DlqEventRepository dlqEventRepository;

    private ConsumerRecord<String, String> buildRecord(String topic) {
        return new ConsumerRecord<>(topic, 0, 100L, "key", "payload");
    }

    @Test
    @DisplayName("Poison Pill → DlqEvent DEAD 저장 (cause 헤더 존재 시 cause로 분류)")
    void consume_poisonPill_savesDead() {
        // given
        given(classifier.isPoisonPillByClassName(anyString())).willReturn(true);
        given(dlqEventRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        // 실제 시나리오: KafkaMessageDeserializationException(FQCN)이 JsonProcessingException(CAUSE_FQCN)을 감싸서 발행됨
        dlqEventConsumer.consume(
                "payload",
                this.buildRecord("notification.send.DLT"),
                "com.fasterxml.jackson.core.JsonProcessingException",
                "com.ghyinc.finance.global.exception.KafkaMessageDeserializationException",
                "파싱 오류"
        );

        // then - cause가 있으면 cause 기준으로 분류
        then(classifier).should().isPoisonPillByClassName("com.fasterxml.jackson.core.JsonProcessingException");
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getErrorType()).isEqualTo("com.fasterxml.jackson.core.JsonProcessingException");
        assertThat(captor.getValue().getStatus()).isEqualTo(DlqStatus.DEAD);
    }

    @Test
    @DisplayName("일시 장애 → DlqEvent PENDING 저장")
    void consume_transientFailure_savePending() {
        // given
        given(classifier.isPoisonPillByClassName(anyString())).willReturn(false);
        given(dlqEventRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        dlqEventConsumer.consume(
                "payload",
                this.buildRecord("loan-limit-completed.DLT"),
                "java.net.ConnectException",
                "org.springframework.kafka.listener.ListenerExecutionFailedException",
                "DB 연결 실패"
        );

        // then
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DlqStatus.PENDING);
    }

    @Test
    @DisplayName("cause 헤더가 없으면(EXCEPTION_CAUSE_FQCN null) 최상위 예외(EXCEPTION_FQCN)로 분류한다")
    void consume_noCauseHeader_fallsBackToTopLevelExceptionFqcn() {
        // given
        // 실제 장애 재현: IllegalArgumentException처럼 cause 없이 직접 던져지는 leaf 예외는
        // DeadLetterPublishingRecoverer가 EXCEPTION_CAUSE_FQCN 헤더를 붙이지 않는다
        given(classifier.isPoisonPillByClassName(anyString())).willReturn(true);
        given(dlqEventRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        dlqEventConsumer.consume(
                "payload",
                this.buildRecord("loan-limit-completed.DLT"),
                null,
                "java.lang.IllegalArgumentException",
                "The given id must not be null"
        );

        // then
        then(classifier).should().isPoisonPillByClassName("java.lang.IllegalArgumentException");
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getErrorType()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(captor.getValue().getStatus()).isEqualTo(DlqStatus.DEAD);
    }
}