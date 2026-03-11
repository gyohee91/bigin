package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "금리 한도조회(응답)")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanLimitResponse {
    @Schema(description = "성공 여부")
    private boolean success;

    public static LoanLimitResponse from(LoanLimitInquiry inquiry) {
        return LoanLimitResponse.builder()
                .success(true)
                .build();
    }
}
