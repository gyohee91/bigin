package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LoanLimitInquiryClaimService {
    private final LoReqtNoGenerator generator;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String claim(Long userId, LoanType loanType, String dedupKey) {
        String inquiryNo = generator.generateGuid("LL");
        loanLimitInquiryRepository.saveAndFlush(
                LoanLimitInquiry.builder()
                        .inquiryNo(inquiryNo)
                        .userId(userId)
                        .loanType(loanType)
                        .status(InquiryStatus.ACCEPTED)
                        .dedupKey(dedupKey)
                        .build()
        );
        return inquiryNo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abort(String inquiryNo) {
        loanLimitInquiryRepository.failIfAccepted(inquiryNo);
    }
}
