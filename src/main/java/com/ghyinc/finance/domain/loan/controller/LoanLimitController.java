package com.ghyinc.finance.domain.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResponse;
import com.ghyinc.finance.domain.loan.service.LoanLimitCallbackService;
import com.ghyinc.finance.domain.loan.service.LoanLimitService;
import com.ghyinc.finance.global.common.ApiCommResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "대출 서비스 API", description = "한도조회 및 대출신청 조회")
@Slf4j
@RestController
@RequestMapping("/api/loan")
@RequiredArgsConstructor
public class LoanLimitController {
    private final LoanLimitService loanLimitService;
    private final LoanLimitCallbackService loanLimitCallbackService;

    @Operation(
            summary = "금리 한도조회",
            description = "제휴 금융사를 대상으로 금리 한도조회 API 전송"
    )
    @ApiResponses(
            @ApiResponse(
                    responseCode = "200",
                    description = "한도조회 요청 성공",
                    content = @Content(schema = @Schema(implementation = LoanLimitResponse.class))
            )
    )
    @PostMapping("/send")
    public ResponseEntity<ApiCommResponse<LoanLimitResponse>> requestCompareLoan(
            @Valid @RequestBody LoanLimitRequest request
    ) {
        LoanLimitResponse response = loanLimitService.requestCompareLoan(request);

        return ResponseEntity.ok(ApiCommResponse.success("한도조회 요청 성공", response));
    }


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
                                    "           \"resultCode\": \"00\"\n" +
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
