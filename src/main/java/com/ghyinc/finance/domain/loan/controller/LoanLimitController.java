package com.ghyinc.finance.domain.loan.controller;

import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResponse;
import com.ghyinc.finance.domain.loan.service.LoanLimitService;
import com.ghyinc.finance.global.common.ApiCommResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "대출 서비스 API", description = "한도조회 및 대출신청 조회")
@Slf4j
@RestController
@RequestMapping("/api/loan")
@RequiredArgsConstructor
public class LoanLimitController {
    private final LoanLimitService loanLimitService;

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
}
