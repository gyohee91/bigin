package com.ghyinc.finance.domain.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitCallbackAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitCallbackRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.service.LoanLimitCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/loan/limit/callback")
@RequiredArgsConstructor
public class LoanLimitCallbackController {
    private final LoanLimitCallbackService loanLimitCallbackService;
    private final LoanLimitCallbackAdaptorFactory callbackAdaptorFactory;

    @Operation(summary = "한도결과 수신 API", description = "금융사로부터 한도조회 결과를 수신합니다")
    @PostMapping
    public ResponseEntity<Void> receiveCallback(
            @Parameter(description = "금융사 코드", example = "LINE_BANK")
            @RequestHeader("X-Partner-Code") String requestPartnerCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "한도결과 요청",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = "{\n" +
                                    "                                      \"loanResult\": [\n" +
                                    "                                        {\n" +
                                    "                                          \"loReqtNo\": \"LR20260311A3F2C891\",\n" +
                                    "                                          \"productCode\": \"KA_PERSONAL_001\",\n" +
                                    "                                          \"amount\": 30000000,\n" +
                                    "                                          \"minRate\": 4.5,\n" +
                                    "                                          \"maxRate\": 8.2,\n" +
                                    "                                          \"resultCode\": \"SUCCESS\"\n" +
                                    "                                        }\n" +
                                    "                                      ]\n" +
                                    "                                    }")
                    )
            )
            @RequestBody JsonNode reqBody
    ) {
        loanLimitCallbackService.process(requestPartnerCode, reqBody);

        return ResponseEntity.ok().build();
    }
}
