package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.global.common.TextUtils;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "금리 한도조회 (Polling)")
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanLimitPollingResponse(
        @Schema(description = "업무 식별번호")
        String inquiryNo,

        @Schema(description = "Inquiry 응답 결과")
        String status,

        // Polling 진행률 정보
        @Schema(description = "전체 상품 수")
        int totalProductCount,

        @Schema(description = "한도결과 수신 완료 수")
        int successProductCount,

        @Schema(description = "진행률")
        int progressRate,

        @Schema(description = "전체 수신 완료 여부")
        boolean allResultReceived,

        // 페이징 정보
        @Schema(description = "한도 결과 목록")
        List<LoanLimitProductResultDto> productResults,
        int currentPage,
        int totalPages,
        long totalElements,
        boolean hasNext,

        @Schema(description = "읽기모델 버전 (다음 폴링 시 ?version=으로 전달)")
        Long version,

        @Schema(description = "false면 이전 응답과 동일(본문 필드는 무시)")
        Boolean changed,

        @Schema(description = "조회 상태(IN_PROGRESS/SUCCESS/PARTIAL_SUCCESS/FAILED)")
        String inquiryStatus,

        @Schema(description = "true면 FE 대기 시간 초과, 폴링 중단 + '지연 중' 안내(실패 아님)")
        Boolean expired,

        @Schema(description = "남은 대기 시간(ms). 종료 상태면 0")
        Long remainingMs,

        @Schema(description = "마감 시간 epoch ms")
        Long deadlineAtEpochMs,

        @Schema(description = "권장 폴링 간격(ms)")
        Long pollIntervalMs
) {
    public static LoanLimitPollingResponse from(LoanLimitInquiry inquiry, Page<LoanLimitProductResultDto> productResults) {
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .status(inquiry.getStatus().name())
                .totalProductCount(inquiry.getTotalProductCount())
                .successProductCount(inquiry.getSuccessProductCount())
                .progressRate(inquiry.getProgressRate())
                .allResultReceived(inquiry.isAllResultReceived())
                .productResults(productResults.getContent())
                .currentPage(productResults.getNumber())
                .totalPages(productResults.getTotalPages())
                .totalElements(productResults.getTotalElements())
                .hasNext(productResults.hasNext())
                .inquiryStatus(inquiry.getStatus().name())
                .build();
    }

    /**
     * 읽기모델/스냅샷 경로 공용 조립. 진행률·페이징 계산과 status/inquiryStatus 채우기를 한 곳에서 한다.
     * (DB 경로 from() 과 같은 필드를 같은 값으로 채워야 한다 - 계약 테스트 참고)
     */
    public static LoanLimitPollingResponse of(String inquiryNo, String status, InquiryProgress progress,
                                              List<LoanLimitProductResultDto> pageItems, long totalElements, int page, int size) {
        int totalPages = size <= 0 ? 1 : (int) Math.ceil((double) totalElements / size);
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiryNo)
                .status(TextUtils.nullIfBlank(status))      // Redis는 값이 없으면 null을 돌려줌
                .inquiryStatus(TextUtils.nullIfBlank(status))
                .totalProductCount(progress.total())
                .successProductCount(progress.success())
                .progressRate(progress.rate())
                .allResultReceived(progress.allReceived())
                .productResults(pageItems)
                .currentPage(page)
                .totalPages(totalPages)
                .totalElements(totalElements)
                .hasNext(page + 1 < totalPages)
                .build();
    }

    public static LoanLimitPollingResponse unchanged(String inquiryNo, long version) {
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiryNo)
                .version(version)
                .changed(false)
                .build();
    }

    public LoanLimitPollingResponse withDeadline(InquiryStatus status, long deadlineAtMs, long pollIntervalMs) {
        if (deadlineAtMs <= 0)  return this;        // 마감 정보를 모르면 판단하지 않는다
        boolean terminal = status != null && status.isTerminal();
        long remainingMs = terminal ? 0 : Math.max(0, deadlineAtMs - System.currentTimeMillis());
        return this.toBuilder()
                .expired(!terminal && remainingMs == 0)
                .remainingMs(remainingMs)
                .deadlineAtEpochMs(deadlineAtMs)
                .pollIntervalMs(pollIntervalMs)
                .build();
    }
}
