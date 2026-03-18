package com.ghyinc.finance.domain.loan.adaptor.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultResponse;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommonResultAdaptor implements LoanLimitResultAdaptor {
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode.isStandard();
    }

    @Override
    public LoanLimitResultRequest convert(JsonNode body) {
        //표준 Layout은 그대로 역직렬화
        return objectMapper.convertValue(body, LoanLimitResultRequest.class);
    }

    @Override
    public ResultResponse buildResponse(boolean success, String resultMessage) {
        return success
                ? LoanLimitResultResponse.success()
                : LoanLimitResultResponse.fail(resultMessage);
    }
}
