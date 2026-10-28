package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 캐시 미스/stale 시 DB에서 전체 스냅샷을 만든다. (statement 2개: inquiry + 전체 목록, COUNT 없음)
 */
@Service
@RequiredArgsConstructor
public class InquirySnapShotLoader {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Transactional(readOnly = true)
    public InquirySnapShot load(String inquiryNo) {
        LoanLimitInquiry inquiry = loanLimitInquiryRepository.findByInquiryNo(inquiryNo)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않은 조회이력입니다: " + inquiryNo));

        // Pageable.unpaged() -> Spring Data가 COUNT 쿼리를 생략한다
        List<LoanLimitProductResultDto> items =
                loanLimitProductResultRepository.findProductResultsByInquiryId(inquiry.getId(), Pageable.unpaged()).getContent();

        return new InquirySnapShot(inquiryNo, inquiry.getTotalProductCount(), inquiry.getStatus().name(), items);
    }
}
