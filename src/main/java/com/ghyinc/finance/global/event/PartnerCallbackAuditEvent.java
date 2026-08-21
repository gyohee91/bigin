package com.ghyinc.finance.global.event;

import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record PartnerCallbackAuditEvent(
        String loReqtNo,
        PartnerCode partnerCode,
        String productCode,
        boolean processed,
        String skipReason,
        LoanLimitResultCode resultCode,
        LocalDateTime occurredAt
) {
}
