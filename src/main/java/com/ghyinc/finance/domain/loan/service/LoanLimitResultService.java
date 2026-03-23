package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitResultRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitResultService {
    private final LoanLimitResultAdaptorFactory resultAdaptorFactory;
    private final ProductRepository productRepository;
    private final LoanLimitResultRepository loanLimitResultRepository;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Transactional
    public ResultResponse responseCompareLoanResult(String requestPartnerCode, JsonNode reqBody) {
        PartnerCode partnerCode = Optional.of(PartnerCode.valueOf(requestPartnerCode))
                .orElseThrow(() -> new InvalidRequestException("유효하지 않은 partnerCode. PartnerCode: " + requestPartnerCode));

        LoanLimitResultAdaptor adaptor = resultAdaptorFactory.getAdaptor(partnerCode);

        try {
            LoanLimitResultRequest request = adaptor.convert(reqBody);
            String loReqtNo = request.getPreScrResultList().stream()
                    .map(LoanLimitResultRequest.PreScrResultList::getLoReqtNo)
                    .findFirst()
                    .orElseThrow(() -> new InvalidRequestException(partnerCode + " loReqtNo 추출 실패"));

            LoanLimitInquiry inquiry = loanLimitResultRepository.findInquiryByPartnerCode(loReqtNo, partnerCode)
                    .orElseThrow(() -> new InvalidRequestException("한도조회 이력 없음. PartnerCode: " + partnerCode));

            Set<String> validProductCodes = productRepository.findActiveByPartnerCodeAndLoanType(partnerCode, inquiry.getLoanType())
                    .stream()
                    .map(Product::getProductCode)
                    .collect(Collectors.toSet());

            request.getPreScrResultList().forEach(item -> {
                if(!validProductCodes.contains(item.getProductCode())) {
                    log.warn("[{}] 유효하지 않은 상품코드. loReqtNo={}, productCode={}", partnerCode, item.getLoReqtNo(), item.getProductCode());
                    return;
                }

                if(loanLimitProductResultRepository.existsByLoReqtNo(item.getLoReqtNo())) {
                    log.warn("[{}] 한도결과 중복 전송됨. loReqtNo={}", partnerCode, item.getLoReqtNo());
                    return;
                }

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

            return adaptor.buildResponse(true, "한도결과 API 정상 처리");
        }
        catch (InvalidRequestException e) {
            log.error("[{}] 한도결과 API 처리 중 오류. message={}", requestPartnerCode, e.getMessage());
            return adaptor.buildResponse(false, e.getMessage());
        }
        catch (Exception e) {
            log.error("[{}] 한도결과 API 처리 중 오류. ", requestPartnerCode, e);
            return adaptor.buildResponse(false, e.getMessage());
        }
    }
}
