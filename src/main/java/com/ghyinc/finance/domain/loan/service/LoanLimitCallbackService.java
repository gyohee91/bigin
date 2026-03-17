package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptorFactory;
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

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitCallbackService {
    private final LoanLimitCallbackAdaptorFactory callbackAdaptorFactory;
    private final LoanLimitResultRepository loanLimitResultRepository;

    @Transactional
    public void process(String requestPartnerCode, JsonNode reqBody) {
        PartnerCode partnerCode = PartnerCode.valueOf(requestPartnerCode);

        LoanLimitCallbackAdaptor adaptor = callbackAdaptorFactory.getAdaptor(partnerCode);

        LoanLimitCallbackRequest request = adaptor.convert(reqBody);
        String loReqtNo = request.getPreScrResultList().stream()
                .map(LoanLimitCallbackRequest.PreScrResultList::getLoReqtNo)
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException(partnerCode + " loReqtNo 추출 실패"));

        LoanLimitInquiry inquiry = loanLimitResultRepository.findInquiryByPartnerCode(loReqtNo, partnerCode)
                        .orElseThrow(() -> new InvalidRequestException("한도조회 이력 없음. PartnerCode: " + partnerCode));

        request.getPreScrResultList().forEach(item -> {
            LoanLimitProductResult productResult = LoanLimitProductResult.builder()
                    .loanLimitInquiry(inquiry)
                    .loReqtNo(item.getLoReqtNo())
                    .productCode(item.getProductCode())
                    .resultCode(item.getResultCode())
                    .amount(item.getAmount())
                    .interestRate(item.getInterestRate())
                    .build();

            inquiry.addProductResult(productResult);
        });
    }
}
