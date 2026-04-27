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
public class LinebankResultAdaptor implements LoanLimitResultAdaptor {
    private final ObjectMapper objectMapper;

    private static final Map<String, LoanLimitResultCode> RESULT_CODE_MAP = Map.of(
            "NFLA00", LoanLimitResultCode.SUCCESS,
            "NFLA01", LoanLimitResultCode.LIMIT_DENIED,
            "NFLA02", LoanLimitResultCode.DUPLICATE_REQUEST,
            "NFLA05", LoanLimitResultCode.INVALID_PRODUCT,
            "NFLA95", LoanLimitResultCode.PARTNER_SYSTEM_ERROR,
            "NFLA99", LoanLimitResultCode.TIMEOUT,
            "NFLA99", LoanLimitResultCode.UNKNOWN_ERROR
    );

    private record LinebankResultRequest(
            List<PreScreeningResult> preScreeningResult
    ) {}

    private record PreScreeningResult(
            String result,
            String ticketId,
            String productName,
            String loanProductId,
            String interestRate,
            String amount
    ) {}

    private record LinebankResultResponse(
            boolean success,
            Map<String, Object> data
    ) implements ResultResponse {}

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.LINE_BANK;
    }

    @Override
    public LoanLimitResultRequest convert(JsonNode body) {
        LinebankResultRequest linebankResultRequest = objectMapper.convertValue(body, LinebankResultRequest.class);

        List<LoanLimitResultRequest.LoanApplyResult> preScreeningResult =
                linebankResultRequest.preScreeningResult().stream()
                        .map(item -> LoanLimitResultRequest.LoanApplyResult.builder()
                                .resultCode(RESULT_CODE_MAP.getOrDefault(item.result(), LoanLimitResultCode.UNKNOWN_ERROR))
                                .loReqtNo(item.ticketId())
                                .productCode(item.loanProductId())
                                .interestRate(Double.parseDouble(item.interestRate))
                                .amount(Long.parseLong(item.amount()))
                                .build())
                        .toList();


        return LoanLimitResultRequest.builder()
                .loanApplyResults(preScreeningResult)
                .build();
    }

    @Override
    public ResultResponse buildResponse(boolean success, String resultMessage) {
        return new LinebankResultResponse(
                success,
                null
        );
    }
}
