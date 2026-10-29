package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import lombok.Builder;

import java.util.Collection;
import java.util.List;

/**
 * 폴링 읽기모델(Redis) 갱신 이벤트. 트랜잭션 커밋 후에만 소비된다
 * @param inquiryNo
 * @param seed      true: 전체 스냅샷(total 포함), false: 변경분(delta)
 * @param total     seed일때 전체 상품 수(delta는 -1 = 변경 없음)
 * @param inquiryStatus null/빈값d이면 상태 유지
 * @param items
 */
@Builder
public record InquiryReadModelEvent(
        String inquiryNo,
        boolean seed,
        int total,
        String inquiryStatus,
        List<LoanLimitProductResultDto> items
) {
    public static InquiryReadModelEvent seed(LoanLimitInquiry inquiry) {
        return InquiryReadModelEvent.builder()
                .inquiryNo(inquiry.getInquiryNo())
                .seed(true)
                .total(inquiry.getTotalProductCount())
                .inquiryStatus(inquiry.getStatus().name())
                .items(toDtos(inquiry.getProductResults()))
                .build();
    }

    public static InquiryReadModelEvent delta(String inquiryNo, String inquiryStatus, List<LoanLimitProductResultDto> items) {
        return InquiryReadModelEvent.builder()
                .inquiryNo(inquiryNo)
                .seed(false)
                .total(-1)
                .inquiryStatus(inquiryStatus)
                .items(items)
                .build();
    }

    public static List<LoanLimitProductResultDto> toDtos(Collection<LoanLimitProductResult> list) {
        return list.stream()
                .map(InquiryReadModelEvent::toDto)
                .toList();
    }

    public static LoanLimitProductResultDto toDto(LoanLimitProductResult productResult) {
        return new LoanLimitProductResultDto(
                productResult.getLoReqtNo(),
                productResult.getPartnerCode(),
                productResult.getProductCode(),
                productResult.getStatus(),
                productResult.getResultCode(),
                productResult.getAmount(),
                productResult.getInterestRate()
        );
    }
}
