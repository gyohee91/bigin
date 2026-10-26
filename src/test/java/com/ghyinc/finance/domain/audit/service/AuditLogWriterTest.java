package com.ghyinc.finance.domain.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ghyinc.finance.domain.audit.entity.AuditLog;
import com.ghyinc.finance.domain.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {
    @Mock
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("write - eventType/aggregateId/JSON payload로 audit_log 1행을 저장한다")
    void write_savesSingleRow() {
        AuditLogWriter writer = new AuditLogWriter(auditLogRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()));

        writer.write("PARTNER_CALLBACK", "LR_1", Map.of("loReqtNo", "LR_1"));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        then(auditLogRepository).should().save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PARTNER_CALLBACK");
        assertThat(captor.getValue().getAggregateId()).isEqualTo("LR_1");
        assertThat(captor.getValue().getPayload()).isEqualTo("{\"loReqtNo\":\"LR_1\"}");
    }
}
