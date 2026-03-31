package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
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
    private final LoanLimitResultAdaptorFactory resultAdaptorFactory;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;

    public ResultResponse responseCompareLoanResult(String requestPartnerCode, JsonNode reqBody) {
        PartnerCode partnerCode = Optional.of(PartnerCode.valueOf(requestPartnerCode))
                .orElseThrow(() -> new InvalidRequestException("유효하지 않은 partnerCode. PartnerCode: " + requestPartnerCode));

        LoanLimitResultAdaptor adaptor = resultAdaptorFactory.getAdaptor(partnerCode);

        try {
            LoanLimitResultRequest request = adaptor.convert(reqBody);

            this.process(partnerCode, request);

            return adaptor.buildResponse(true, "한도결과 API 정상 처리");
        }
        catch (InvalidRequestException e) {
            log.error("[{}] 한도결과 API 처리 중 오류. message={}", requestPartnerCode, e.getMessage());
            return adaptor.buildResponse(false, e.getMessage());
        }
        catch (Exception e) {
            log.error("[{}] 한도결과 API 처리 중 오류. ", requestPartnerCode, e);
            return adaptor.buildResponse(false, "처리 중 오류가 발생했습니다");
        }
    }

    @Transactional
    private void process(PartnerCode partnerCode, LoanLimitResultRequest request) {
        request.getPreScrResultList().forEach(item -> {
            //loReqtNo와 productCode로 선저장된 ProductResult 조회
            LoanLimitProductResult productResult = loanLimitProductResultRepository.findByLoReqtNoAndProductCode(item.getLoReqtNo(), item.getProductCode())
                    .orElseThrow(() -> new InvalidRequestException("존재하지 않는 식별번호&상품코드. loReqtNo: " + item.getLoReqtNo() + ", productCode: " + item.getProductCode()));

            //비관적 Lock으로 동시 수신 시 순차 처리 보장
            LoanLimitInquiry loanLimitInquiry = loanLimitProductResultRepository.findInquiryByLoReqtNoAndProduceCodeWithLock(item.getLoReqtNo(), item.getProductCode())
                    .orElseThrow(() -> new InvalidRequestException("존재하지 않는 한도조회 이력"));

            //중복 or 처리불가 상태 체크
            if(productResult.getStatus() != PartnerInquiryStatus.SEND_SUCCESS) {
                log.warn("[{}] 처리 불가 상태의 결과 수신. loReqtNo={}, status={}",
                        partnerCode, item.getLoReqtNo(), productResult.getStatus());

                if(productResult.getStatus() == PartnerInquiryStatus.SUCCESS) {
                    log.warn("[{}] 중복 수신. loReqtNo={}",
                            partnerCode, item.getLoReqtNo());
                }

                return;
            }

            //한도결과 UPDATE
            loanLimitInquiry.incrementSuccessCount();
            productResult.updateResult(item.getResultCode(), item.getAmount(), item.getInterestRate());
        });
    }
}
