package com.ghyinc.finance.global.event;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Builder;

import java.util.List;

/**
 * 한도조회 LoanLimitSenderService.inquiry에 대한 이벤트 DTO
 */
@Builder
public record LoanLimitInquiryCreatedEvent(
        Long id,
        List<PartnerCode> activePartnerCodes,
        LoanLimitAdaptorRequest adaptorRequest
) { }
