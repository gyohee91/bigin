package com.ghyinc.finance.domain.loan.adaptor.callback.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaobankCallbackAdaptor implements LoanLimitCallbackAdaptor {
    private final ObjectMapper objectMapper;

    private record KakaobankCallbackRequest(
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
    ) { }

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.KAKAO_BANK;
    }

    @Override
    public LoanLimitCallbackRequest convert(JsonNode body) {
        KakaobankCallbackRequest kakaobankRequest = objectMapper.convertValue(body, KakaobankCallbackRequest.class);

        List<LoanLimitCallbackRequest.PreScrResultList> preScrResultLists = kakaobankRequest.products().stream()
                .map(item -> {
                    return LoanLimitCallbackRequest.PreScrResultList.builder()
                            .loReqtNo(item.iqryDmanNo)
                            .productCode(item.alncGdsUnqCd)
                            .resultCode(Objects.equals("CP0000", item.rsltCd) ? LoanLimitResultCode.SUCCESS : LoanLimitResultCode.UNKNOWN_ERROR)
                            .amount(item.loanLimitAmt)
                            .interestRate(item.lastLoanIntr)
                            .build();
                })
                .toList();

        return LoanLimitCallbackRequest.builder()
                .preScrResultList(preScrResultLists)
                .build();
    }
}
