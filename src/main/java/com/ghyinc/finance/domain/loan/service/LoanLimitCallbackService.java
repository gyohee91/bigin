package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.LoanLimitResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitCallbackService {
    private final LoanLimitResultRepository loanLimitResultRepository;

    @Transactional
    public void process(PartnerCode partnerCode, LoanLimitCallbackRequest request) {
        LoanLimitInquiry inquiry = loanLimitResultRepository.findInquiryByPartnerCode(partnerCode)
                        .orElseThrow(() -> new InvalidRequestException("한도조회 이력 없음. PartnerCode: " + partnerCode));

        request.getRequestProductResult().forEach(item -> {
            LoanLimitProductResult productResult = LoanLimitProductResult.builder()
                    .loanLimitInquiry(inquiry)
                    .loReqtNo(item.getLoReqtNo())
                    .productCode(item.getProductCode())
                    .amount(item.getAmount())
                    .interestRate(item.getInterestRate())
                    .build();

            inquiry.addProductResult(productResult);
        });
    }
}
