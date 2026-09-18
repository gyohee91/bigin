package com.ghyinc.finance.domain.audit.event;

import com.ghyinc.finance.domain.audit.entity.AuditLog;
import com.ghyinc.finance.domain.audit.repository.AuditLogRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AuditLogConsumerTest {
    @InjectMocks
    private AuditLogConsumer auditLogConsumer;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("파트너 전송 이력 배치 수신 시 eventType=PARTNER_TRANSMISSION으로 일괄 저장한다")
    void consumeTransmission_savesAuditLogsWithTransmissionEventType() {
        // given
        String payload = "{\"inquiryNo\":\"LL2026082000003\",\"partnerCode\":\"KAKAO_BANK\",\"success\":true}";
        given(auditLogRepository.saveAll(anyList())).willAnswer(i -> i.getArgument(0));

        // when
        auditLogConsumer.consumeTransmission(
                List.of(new ConsumerRecord<>("audit.partner-transmission", 1, 6L, "LL2026082000003", payload))
        );

        // then
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        then(auditLogRepository).should().saveAll(captor.capture());

        List<AuditLog> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getEventType()).isEqualTo("PARTNER_TRANSMISSION");
        assertThat(saved.get(0).getAggregateId()).isEqualTo("LL2026082000003");
        assertThat(saved.get(0).getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("콜백 수신 이력 배치 수신 시 eventType=PARTNER_CALLBACK으로 일괄 저장한다")
    void consumeCallback_savesAuditLogsWithCallbackEventType() {
        // given
        String payload = "{\"inquiryNo\":\"LR2026082000011\",\"partnerCode\":\"KAKAO_BANK\",\"success\":true}";
        given(auditLogRepository.saveAll(anyList())).willAnswer(i -> i.getArgument(0));

        // when
        auditLogConsumer.consumeCallback(
                List.of(new ConsumerRecord<>("audit.partner-callback", 1, 6L, "LR2026082000011", payload))
        );

        // then
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        then(auditLogRepository).should().saveAll(captor.capture());

        List<AuditLog> saved = captor.getValue();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getEventType()).isEqualTo("PARTNER_CALLBACK");
        assertThat(saved.get(0).getAggregateId()).isEqualTo("LR2026082000011");
        assertThat(saved.get(0).getPayload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("배치 내 여러 레코드를 한 번의 saveAll 호출로 저장한다")
    void consumeTransmission_multipleRecords_savedInSingleBatch() {
        // given
        given(auditLogRepository.saveAll(anyList())).willAnswer(i -> i.getArgument(0));

        // when
        auditLogConsumer.consumeTransmission(
                List.of(
                        new ConsumerRecord<>("audit.partner-transmission", 0, 10L, "LL0001", "{}"),
                        new ConsumerRecord<>("audit.partner-transmission", 1, 11L, "LL0002", "{}"),
                        new ConsumerRecord<>("audit.partner-transmission", 2, 12L, "LL0003", "{}")
                )
        );

        // then: saveAll이 정확히 1번, 레코드 3건이 한 리스트로 호출됨 (건당 save() 아님)
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        then(auditLogRepository).should().saveAll(captor.capture());
        then(auditLogRepository).shouldHaveNoMoreInteractions();

        assertThat(captor.getValue()).hasSize(3)
                .extracting(AuditLog::getAggregateId)
                .containsExactly("LL0001", "LL0002", "LL0003");
    }

    @Test
    @DisplayName("record의 key(aggregateId)가 null이어도 예외없이 저장한다")
    void consumeTransmission_nullKey_stillSaves() {
        // given: OutboxEventWriter.enqueue()의 aggregateId가 항상 채워주긴 하지만
        // 방어적으로 key가 없는 극단적 케이스도 저장 자체는 실패하지 않는지 확인
        String payload = "{}";
        given(auditLogRepository.saveAll(anyList())).willAnswer(i -> i.getArgument(0));

        // when & then (예외 없이 통과하면 성공)
        auditLogConsumer.consumeTransmission(
                List.of(new ConsumerRecord<>("audit.partner-transmission", 1, 6L, null, payload))
        );

        then(auditLogRepository).should().saveAll(any(List.class));
    }
}
