package com.ghyinc.finance.domain.loan.adaptor.callback.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class KakaobankCallbackAdaptor implements LoanLimitCallbackAdaptor {
    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.KAKAO_BANK;
    }

    @Override
    public String extractLoReqtNo(JsonNode reqBody) {
        return reqBody.path("products")
                .get(0)
                .path("iqry_dman_no")
                .asText();
    }

    @Override
    public LoanLimitCallbackRequest convert(JsonNode body) {
        return null;
    }
}
