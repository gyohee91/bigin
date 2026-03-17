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
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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

    @Operation(summary = "한도결과 수신 API", description = "금융사로부터 한도조회 결과를 수신")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "처리 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping
    public ResponseEntity<Void> receiveCallback(
            @Parameter(description = "금융사 코드", example = "LINE_BANK")
            @RequestHeader("X-Partner-Code") String requestPartnerCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "한도결과 요청",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = "" +
                                    "{" +
                                    "   \"preScrResultList\": [" +
                                    "       {\n" +
                                    "           \"loReqtNo\": \"LR20260311A3F2C891\",\n" +
                                    "           \"productCode\": \"KA_PERSONAL_001\",\n" +
                                    "           \"resultCode\": \"00\",\n" +
                                    "           \"amount\": 30000000,\n" +
                                    "           \"interestRate\": 4.5,\n" +
                                    "           \"resultCode\": \"SUCCESS\"\n" +
                                    "       }\n" +
                                    "   ]\n" +
                                    "}")
                    )
            )
            @RequestBody JsonNode reqBody
    ) {
        loanLimitCallbackService.process(requestPartnerCode, reqBody);

        return ResponseEntity.ok().build();
    }
}
