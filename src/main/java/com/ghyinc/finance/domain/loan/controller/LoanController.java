package com.ghyinc.finance.domain.loan.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.service.LoanApplyService;
import com.ghyinc.finance.domain.loan.service.LoanLimitResultService;
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
public class LoanController {
    private final LoanLimitService loanLimitService;    //한도조회
    private final LoanLimitResultService loanLimitResultService;    //한도결과
    private final LoanApplyService loanApplyService;    //대출신청

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
    @PostMapping("/request-compare-loan")
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
    @PostMapping("/response-compare-loan-result")
    public ResponseEntity<ResultResponse> responseCompareLoanResult(
            @Parameter(description = "금융사 코드", example = "LINE_BANK")
            @RequestHeader("X-Partner-Code") String requestPartnerCode,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "한도결과 요청",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = "{" +
                                    "   \"preScrResultList\": [" +
                                    "       {" +
                                    "           \"loReqtNo\": \"LR20260311A3F2C891\"," +
                                    "           \"productCode\": \"KA_PERSONAL_001\"," +
                                    "           \"resultCode\": \"00\"," +
                                    "           \"amount\": 30000000," +
                                    "           \"interestRate\": 4.5," +
                                    "           \"resultCode\": \"00\"" +
                                    "       }" +
                                    "   ]" +
                                    "}")
                    )
            )
            @RequestBody JsonNode reqBody
    ) {
       ResultResponse response = loanLimitResultService.responseCompareLoanResult(requestPartnerCode, reqBody);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "대출신청 API", description = "고객이 선택한 한도결과 건에 대한 대출신청 진행")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "처리 성공",
                    content = @Content(schema = @Schema(implementation = LoanApplyResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/apply")
    public ResponseEntity<ApiCommResponse<LoanApplyResponse>> apply(
            @Valid @RequestBody LoanApplyRequest request
    ) {
        LoanApplyResponse response = loanApplyService.apply(request);

        return ResponseEntity.ok(ApiCommResponse.success("대출신청 성공", response));
    }
}
