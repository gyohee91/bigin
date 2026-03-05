package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Schema(description = "금리 한도조회(요청)")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanLimitRequest {
    @Schema(description = "고객명", example = "윤교희")
    private String name;

    @Schema(description = "주민번호", example = "9102131012345")
    private String rrno;

    @Schema(description = "직업 구분", example = "EMPLOYEE")
    private JobType jobType;

    @Schema(description = "대출 유형", example = "PERSONAL_CREDIT")
    private LoanType loanType;
}
