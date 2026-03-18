package com.ghyinc.finance.domain.loan.adaptor.callback.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommonCallbackAdaptor implements LoanLimitCallbackAdaptor {
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode.isStandard();
    }

    @Override
    public LoanLimitCallbackRequest convert(JsonNode body) {
        //표준 Layout은 그대로 역직렬화
        return objectMapper.convertValue(body, LoanLimitCallbackRequest.class);
    }
}
