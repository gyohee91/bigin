package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaobankResultAdaptor implements LoanLimitResultAdaptor {
    private final ObjectMapper objectMapper;

    private static final Map<String, LoanLimitResultCode> RESULT_CODE_MAP = Map.of(
            "CP0000", LoanLimitResultCode.SUCCESS,
            "CP1009", LoanLimitResultCode.LIMIT_DENIED,
            "CP1011", LoanLimitResultCode.DUPLICATE_REQUEST,
            "CP4011", LoanLimitResultCode.INVALID_PRODUCT,
            "CP5001", LoanLimitResultCode.PARTNER_SYSTEM_ERROR,
            "CP5002", LoanLimitResultCode.TIMEOUT,
            "CP5000", LoanLimitResultCode.UNKNOWN_ERROR
    );

    private record KakaobankResultRequest(
            List<Product> products
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record Product(
            String iqryDmanNo,
            String alncGdsUnqCd,
            String rsltCd,
            long loanLimitAmt,
            double lastLoanIntr,
            String loanTrmMcnt
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record KakaobankResultResponse(
            String rsltCd
    ) implements ResultResponse {}

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.KAKAO_BANK;
    }

    @Override
    public LoanLimitResultRequest convert(JsonNode body) {
        KakaobankResultRequest kakaobankRequest = objectMapper.convertValue(body, KakaobankResultRequest.class);

        List<LoanLimitResultRequest.PreScrResultList> preScrResultLists = kakaobankRequest.products().stream()
                .map(item -> LoanLimitResultRequest.PreScrResultList.builder()
                        .loReqtNo(item.iqryDmanNo)
                        .productCode(item.alncGdsUnqCd)
                        .resultCode(RESULT_CODE_MAP.getOrDefault(item.rsltCd, LoanLimitResultCode.UNKNOWN_ERROR))
                        .amount(item.loanLimitAmt)
                        .interestRate(item.lastLoanIntr)
                        .build())
                .toList();

        return LoanLimitResultRequest.builder()
                .preScrResultList(preScrResultLists)
                .build();
    }

    @Override
    public ResultResponse buildResponse(boolean success, String resultMessage) {
        return new KakaobankResultResponse(
                success ? "CP0000" : "CP9999"
        );
    }
}
