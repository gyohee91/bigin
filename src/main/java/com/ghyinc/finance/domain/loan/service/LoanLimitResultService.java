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
import java.util.Map;
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

            request.getPreScrResultList().forEach(item -> {
                LoanLimitProductResult productResult = loanLimitProductResultRepository.findByLoReqtNoAndProductCode(item.getLoReqtNo(), item.getProductCode())
                        .orElseThrow(() -> new InvalidRequestException("존재하지 않는 식별번호&상품코드. loReqtNo: " + item.getLoReqtNo() + ", productCode: " + item.getProductCode()));

                productResult.updateResult(item.getResultCode(), item.getAmount(), item.getInterestRate());
            });
            /*
            LoanLimitInquiry inquiry = loanLimitProductResultRepository.findPartnerCodeByLoReqtNo(partnerCode)
                    .orElseThrow(() -> new InvalidRequestException("한도조회 이력 없음. PartnerCode: " + partnerCode));

            Set<String> validProductCodes = productRepository.findActiveByPartnerCodeAndLoanType(partnerCode, inquiry.getLoanType())
                    .stream()
                    .map(Product::getProductCode)
                    .collect(Collectors.toSet());

            // REQ Data에 대한 유효성 체크
            Map<Boolean, List<LoanLimitResultRequest.PreScrResultList>> validRequested = request.getPreScrResultList().stream()
                    .collect(Collectors.partitioningBy(item ->
                            validProductCodes.contains(item.getProductCode()) &&
                                    !loanLimitProductResultRepository.existsByLoReqtNo(item.getLoReqtNo())
                    ));

            validRequested.get(true).forEach(item -> {
                LoanLimitProductResult productResult = loanLimitProductResultRepository.findByLoReqtNo(item.getLoReqtNo())
                        .orElseThrow(() -> new InvalidRequestException("존재하지 않는 식별번호. loReqtNo: " + item.getLoReqtNo()));

                productResult.updateResult(item.getResultCode(), item.getAmount(), item.getInterestRate());
            });
            */
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
