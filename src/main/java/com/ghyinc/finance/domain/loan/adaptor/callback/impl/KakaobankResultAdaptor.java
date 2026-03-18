package com.ghyinc.finance.domain.loan.adaptor.callback.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
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
public class KakaobankResultAdaptor implements LoanLimitResultAdaptor {
    private final ObjectMapper objectMapper;

    private record KakaobankResultequest(
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
    public LoanLimitResultRequest convert(JsonNode body) {
        KakaobankResultequest kakaobankRequest = objectMapper.convertValue(body, KakaobankResultequest.class);

        List<LoanLimitResultRequest.PreScrResultList> preScrResultLists = kakaobankRequest.products().stream()
                .map(item -> {
                    return LoanLimitResultRequest.PreScrResultList.builder()
                            .loReqtNo(item.iqryDmanNo)
                            .productCode(item.alncGdsUnqCd)
                            .resultCode(Objects.equals("CP0000", item.rsltCd) ? LoanLimitResultCode.SUCCESS : LoanLimitResultCode.UNKNOWN_ERROR)
                            .amount(item.loanLimitAmt)
                            .interestRate(item.lastLoanIntr)
                            .build();
                })
                .toList();

        return LoanLimitResultRequest.builder()
                .preScrResultList(preScrResultLists)
                .build();
    }
}
