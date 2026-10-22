package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

@Schema(description = "한도조회 결과 (결과 화면)")
@Builder
public record LoanLimitSummaryResponse(
        String inquiryNo,
        LoanType loanType,
        InquiryStatus status,
        @Schema(description = "상품별 대출 가능 금융사 그룹")
        List<AvailablePartnerSummary> availablePartners,
        @Schema(description = "대출 불가 금융사")
        List<UnavailablePartnerSummary> unavailablePartners
) {
}
