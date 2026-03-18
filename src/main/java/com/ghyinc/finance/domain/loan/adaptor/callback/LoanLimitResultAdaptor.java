package com.ghyinc.finance.domain.loan.adaptor.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

public interface LoanLimitResultAdaptor {
    boolean supports(PartnerCode partnerCode);
    LoanLimitResultRequest convert(JsonNode body);  //원문 -> 표준 DTO
    ResultResponse buildResponse(boolean success, String resultMessage);
}
