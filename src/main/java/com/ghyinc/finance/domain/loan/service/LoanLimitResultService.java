package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.LoanLimitResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitResultService {
    private final LoanLimitCallbackAdaptorFactory callbackAdaptorFactory;
    private final LoanLimitResultRepository loanLimitResultRepository;

    @Transactional
    public LoanLimitResultResponse responseCompareLoanResult(String requestPartnerCode, JsonNode reqBody) {
        try {
            PartnerCode partnerCode = Optional.of(PartnerCode.valueOf(requestPartnerCode))
                    .orElseThrow(() -> new InvalidRequestException("유효하지 않은 partnerCode. PartnerCode: " + requestPartnerCode));

            LoanLimitResultAdaptor adaptor = callbackAdaptorFactory.getAdaptor(partnerCode);

            LoanLimitResultRequest request = adaptor.convert(reqBody);
            String loReqtNo = request.getPreScrResultList().stream()
                    .map(LoanLimitResultRequest.PreScrResultList::getLoReqtNo)
                    .findFirst()
                    .orElseThrow(() -> new InvalidRequestException(partnerCode + " loReqtNo 추출 실패"));

            LoanLimitInquiry inquiry = loanLimitResultRepository.findInquiryByPartnerCode(loReqtNo, partnerCode)
                    .orElseThrow(() -> new InvalidRequestException("한도조회 이력 없음. PartnerCode: " + partnerCode));

            request.getPreScrResultList().forEach(item -> {
                LoanLimitProductResult productResult = LoanLimitProductResult.builder()
                        .loanLimitInquiry(inquiry)
                        .loReqtNo(item.getLoReqtNo())
                        .partnerCode(partnerCode)
                        .productCode(item.getProductCode())
                        .resultCode(item.getResultCode())
                        .amount(item.getAmount())
                        .interestRate(item.getInterestRate())
                        .build();

                inquiry.addProductResult(productResult);
            });

            return LoanLimitResultResponse.success();
        }
        catch (InvalidRequestException e) {
            log.error("[{}] 한도결과 API 처리 중 오류. message={}", requestPartnerCode, e.getMessage());
            return LoanLimitResultResponse.fail(e.getMessage());
        }
        catch (Exception e) {
            log.error("[{}] 한도결과 API 처리 중 오류. ", requestPartnerCode, e);
            return LoanLimitResultResponse.fail(e.getMessage());
        }
    }
}
