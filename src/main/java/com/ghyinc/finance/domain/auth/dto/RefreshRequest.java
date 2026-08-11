package com.ghyinc.finance.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Token 재발급 (요청)")
public record RefreshRequest(
        @Schema(description = "refresh token")
        String refreshToken
) {
}
