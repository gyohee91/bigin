package com.ghyinc.finance.domain.auth.controller;

import com.ghyinc.finance.domain.auth.dto.LoginRequest;
import com.ghyinc.finance.domain.auth.dto.RefreshRequest;
import com.ghyinc.finance.domain.auth.dto.TokenResponse;
import com.ghyinc.finance.domain.auth.service.AuthService;
import com.ghyinc.finance.global.common.ApiCommResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @Operation(
            summary = "로그인",
            description = "사용자의 인증을 처리 후 Token을 발행한다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "인증 요청 성공",
                    content = @Content(schema = @Schema(implementation = TokenResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/login")
    public ResponseEntity<ApiCommResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request.mobile(), request.password());

        return ResponseEntity.ok(ApiCommResponse.success("Login 성공", response));
    }

    @Operation(
            summary = "토큰 재발급",
            description = "사용자의 토큰을 재발급한다"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "토큰 재발급 요청 성공",
                    content = @Content(schema = @Schema(implementation = TokenResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/refresh")
    public ResponseEntity<ApiCommResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
        TokenResponse response = authService.refresh(request.refreshToken());

        return ResponseEntity.ok(ApiCommResponse.success("토큰 재발급 성공", response));
    }

    @Operation(
            summary = "Logout",
            description = "사용자의 Logout을 처리한다"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Logout 성공"),
            @ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @ApiResponse(responseCode = "500", description = "서버 오류")
    })
    @PostMapping("/logout")
    public ResponseEntity<ApiCommResponse<Void>> logout(
            @RequestHeader("Authorization") String authorizationHeader,
            Authentication authentication
    ) {
        Long memberId = Long.valueOf(authentication.getName());
        String accessToken = authorizationHeader.replace("Bearer ", "");
        authService.logout(memberId, accessToken);
        return ResponseEntity.ok(ApiCommResponse.success("Logout 성공", null));
    }

}
