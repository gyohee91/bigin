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
    @DisplayName("Poison Pill → DlqEvent DEAD 저장")
    void consume_poisonPill_savesDead() {
        // given
        given(classifier.isPoisonPillByClassName(anyString())).willReturn(true);
        given(dlqEventRepository.save(any())).willAnswer(i -> i.getArgument(0));

        // when
        dlqEventConsumer.consume(
                "payload",
                this.buildRecord("notification.send.DLT"),
                "com.fasterxml.jackson.core.JsonProcessingException",
                "파싱 오류"
        );

        // then
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());
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
                "DB 연결 실패"
        );

        // then
        ArgumentCaptor<DlqEvent> captor = ArgumentCaptor.forClass(DlqEvent.class);
        then(dlqEventRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DlqStatus.PENDING);
    }
}