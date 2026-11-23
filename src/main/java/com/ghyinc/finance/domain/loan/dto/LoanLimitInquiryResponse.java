package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.config.InquiryPollingProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Schema(description = "금리 한도조회 (응답)")
@Builder
public record LoanLimitInquiryResponse(
        @Schema(description = "업무 식별번호")
        String inquiryNo,

        @Schema(description = "성공 여부")
        boolean success,

        @Schema(description = "FE가 폴링을 계속해도 되는 남은 시간(ms). 0 이면 즉시 종료 판단")
        long remainingMs,

        @Schema(description = "마감 시각(epoch ms). 화면 복원용 보조값")
        long deadlineAtEpochMs,

        @Schema(description = "권장 폴링 간격(ms)")
        long pollIntervalMs
) {
    public static LoanLimitInquiryResponse from(LoanLimitInquiry inquiry, InquiryPollingProperties properties) {
        LocalDateTime baseAt = inquiry.getCreatedAt() != null ? inquiry.getCreatedAt() : LocalDateTime.now();
        LocalDateTime deadline = baseAt.plus(properties.clientWaitLimit());
        return LoanLimitInquiryResponse.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .success(true)
                .remainingMs(Math.max(0, Duration.between(LocalDateTime.now(), deadline).toMillis()))
                .deadlineAtEpochMs(deadline.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .pollIntervalMs(properties.pollInterval().toMillis())
                .build();
    }
}
