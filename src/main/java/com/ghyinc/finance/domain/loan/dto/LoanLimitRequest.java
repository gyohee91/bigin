package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    @NotBlank(message = "고객명은 필수입니다")
    private String name;

    @Schema(description = "고객ID", example = "1")
    private Long userId;

    @Schema(description = "주민번호", example = "9102131012345")
    @NotBlank(message = "주민번호는 필수입니다")
    private String rrno;

    @Schema(description = "CI", example = "wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
    private String ci;

    @Schema(description = "직업 구분", example = "EMPLOYEE")
    @NotBlank(message = "직업 구분은 필수입니다")
    private JobType jobType;

    @Schema(description = "직장명", example = "오케이저축은행")
    private String jobName;

    @Schema(description = "차량번호", example = "12가1234")
    private String carNo;

    @Schema(description = "대출 유형", example = "PERSONAL_CREDIT")
    @NotNull(message = "대출 유형은 필수입니다")
    private LoanType loanType;

    @Schema(description = "법정동코드", example = "1135010500")
    private String kbIdentityCode;

    @Schema(description = "주소", example = "수원시 영통구 영통로90번길 4-27 ...")
    private String address;
}
