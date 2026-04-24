package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.LocalDateTime;

@Schema(description = "금리 한도조회(요청)")
@Builder
public record LoanLimitRequest(
        @Schema(description = "고객명", example = "윤교희")
        @NotBlank(message = "고객명은 필수입니다")
        String name,

        @Schema(description = "고객ID", example = "1")
        Long userId,

        @Schema(description = "주민번호", example = "9102131012345")
        @NotBlank(message = "주민번호는 필수입니다")
        String rrno,

        @Schema(description = "CI", example = "wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
        String ci,

        @Schema(description = "직업 구분", example = "EMPLOYEE")
        @NotBlank(message = "직업 구분은 필수입니다")
        JobType jobType,

        @Schema(description = "직장명", example = "오케이저축은행")
        String jobName,

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyyMM", timezone = "Asia/Seoul")
        @Schema(description = "입사년월(개업년월)", example = "202604")
        String joinDate,

        @Schema(description = "차량번호", example = "12가1234")
        String carNo,

        @Schema(description = "대출 유형", example = "PERSONAL_CREDIT")
        @NotNull(message = "대출 유형은 필수입니다")
        LoanType loanType,

        @Schema(description = "법정동코드", example = "1135010500")
        String kbIdentityCode,

        @Schema(description = "주소", example = "수원시 영통구 영통로90번길 4-27 ...")
        String address,

        @Schema(description = "신용정보 수집 이용 제공 동의 여부", example = "true")
        boolean agreePersonalCreditInfo,

        @Schema(description = "신용정보 수집 이용 제공 동의 시간", example = "2026-04-16T13:57:53")
        LocalDateTime agreePersonalCreditTime
) {
}
