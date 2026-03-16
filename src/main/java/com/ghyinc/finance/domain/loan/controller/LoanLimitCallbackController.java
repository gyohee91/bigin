package com.ghyinc.finance.domain.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.service.LoanLimitCallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/loan/limit/callback")
@RequiredArgsConstructor
public class LoanLimitCallbackController {
    private final LoanLimitCallbackService loanLimitCallbackService;
    private final LoanLimitCallbackAdaptorFactory callbackAdaptorFactory;

    @PostMapping
    public ResponseEntity<Void> receiveCallback(
            @RequestHeader("X-Partner-Code") String requestPartnerCode,
            @RequestBody JsonNode reqBody
    ) {
        PartnerCode partnerCode = PartnerCode.valueOf(requestPartnerCode);

        LoanLimitCallbackAdaptor adaptor = callbackAdaptorFactory.getAdaptor(null);
        String loReqtNo = adaptor.extractLoReqtNo(reqBody);

        LoanLimitCallbackRequest request = adaptor.convert(reqBody);
        loanLimitCallbackService.process(partnerCode, request);

        return ResponseEntity.ok().build();
    }
}
