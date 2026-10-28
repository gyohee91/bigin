package com.ghyinc.finance.domain.loan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "금리 한도조회 (Polling)")
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoanLimitPollingResponse(
        @Schema(description = "업무 식별번호")
        String inquiryNo,

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
        String inquiryStatus
) {
    public static LoanLimitPollingResponse from(LoanLimitInquiry inquiry, Page<LoanLimitProductResultDto> productResults) {
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .totalProductCount(inquiry.getTotalProductCount())
                .successProductCount(inquiry.getSuccessProductCount())
                .progressRate(inquiry.getProgressRate())
                .allResultReceived(inquiry.isAllResultReceived())
                .productResults(productResults.getContent())
                .currentPage(productResults.getNumber())
                .totalPages(productResults.getTotalPages())
                .totalElements(productResults.getTotalElements())
                .hasNext(productResults.hasNext())
                .build();
    }

    public static LoanLimitPollingResponse unchanged(String inquiryNo, long version) {
        return LoanLimitPollingResponse.builder()
                .inquiryNo(inquiryNo)
                .version(version)
                .changed(false)
                .build();
    }
}
