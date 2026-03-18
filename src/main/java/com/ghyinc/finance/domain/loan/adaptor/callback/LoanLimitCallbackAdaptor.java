package com.ghyinc.finance.domain.loan.adaptor.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

public interface LoanLimitCallbackAdaptor {
    boolean supports(PartnerCode partnerCode);
    LoanLimitCallbackRequest convert(JsonNode body);  //원문 -> 표준 DTO
}
