package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Schema(description = "대출 불가 금융사")
@Builder
public record UnavailablePartnerSummary(
        @Schema(description = "금융사 코드")
        PartnerCode partnerCode,
        @Schema(description = "금융사명")
        String partnerName,
        @Schema(description = "대출 불가 사유")
        String reason
) {
}
