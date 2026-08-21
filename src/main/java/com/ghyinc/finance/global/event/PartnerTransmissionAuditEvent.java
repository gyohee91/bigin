package com.ghyinc.finance.global.event;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record PartnerTransmissionAuditEvent(
        String inquiryNo,
        PartnerCode partnerCode,
        boolean success,
        String failReason,
        long resTimeMs,
        LocalDateTime occurredAt
) {
    public static PartnerTransmissionAuditEvent from(String inquiryNo, LoanLimitAdaptorResponse response) {
        return PartnerTransmissionAuditEvent.builder()
                .inquiryNo(inquiryNo)
                .partnerCode(response.partnerCode())
                .success(response.success())
                .failReason(response.failReason())
                .resTimeMs(response.resTimeMs())
                .occurredAt(LocalDateTime.now())
                .build();
    }
}
