package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class TossbankResultAdaptor implements LoanLimitResultAdaptor {
    private final ObjectMapper objectMapper;

    private static final Map<String, LoanLimitResultCode> RESULT_CODE_MAP = Map.of(
            "TLA00", LoanLimitResultCode.SUCCESS,
            "TLA04", LoanLimitResultCode.LIMIT_DENIED,
            "TLA06", LoanLimitResultCode.DUPLICATE_REQUEST,
            "TLA02", LoanLimitResultCode.INVALID_PRODUCT,
            "TLA10", LoanLimitResultCode.PARTNER_SYSTEM_ERROR,
            "TLA08", LoanLimitResultCode.TIMEOUT,
            "TLA99", LoanLimitResultCode.UNKNOWN_ERROR
    );

    private record TossbankResultRequest(
            List<PreScreeningResult> preScreeningResult
    ) {}

    private record PreScreeningResult(
            String result,
            String loanReqNo,
            String loanProductId,
            double interestRate,
            long amount
    ) {}

    private record TossbankResultResponse(
            String code,
            Map<String, Object> data
    ) implements ResultResponse {}

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.TOSS_BANK;
    }

    @Override
    public LoanLimitResultRequest convert(JsonNode body) {
        TossbankResultRequest tossbankRequest = objectMapper.convertValue(body, TossbankResultRequest.class);

        List<LoanLimitResultRequest.LoanApplyResult> preScrResultLists =
                tossbankRequest.preScreeningResult().stream()
                        .map(item -> LoanLimitResultRequest.LoanApplyResult.builder()
                                .loReqtNo(item.loanReqNo)
                                .productCode(item.loanProductId)
                                .resultCode(RESULT_CODE_MAP.getOrDefault(item.result, LoanLimitResultCode.UNKNOWN_ERROR))
                                .amount(item.amount)
                                .interestRate(item.interestRate)
                                .build())
                        .toList();

        return LoanLimitResultRequest.builder()
                .loanApplyResults(preScrResultLists)
                .build();
    }

    @Override
    public ResultResponse buildResponse(boolean success, String resultMessage) {
        return new TossbankResultResponse(
                success ? "TEL000" : "TEL999",
                null
        );
    }
}
