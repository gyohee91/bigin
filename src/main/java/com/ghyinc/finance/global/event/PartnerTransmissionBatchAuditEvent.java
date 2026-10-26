package com.ghyinc.finance.global.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 조회 1건에 대한 전체 파트너 전송 결과 묶음 (감사 로그 1행 / Outbox 1건 / Kafka 메시지 1건).
 */
public record PartnerTransmissionBatchAuditEvent(
        String inquiryNo,
        List<PartnerTransmissionAuditEvent> transmissions,
        LocalDateTime occurredAt
) {
    public static PartnerTransmissionBatchAuditEvent of(String inquiryNo, List<PartnerTransmissionAuditEvent> transmissions) {
        return new PartnerTransmissionBatchAuditEvent(inquiryNo, transmissions, LocalDateTime.now());
    }
}
