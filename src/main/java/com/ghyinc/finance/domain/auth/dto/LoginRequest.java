package com.ghyinc.finance.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "인증 (요청)")
public record LoginRequest(
        @Schema(description = "휴대폰번호", example = "01012341234")
        String mobile,

        @Schema(description = "password", example = "1234")
        String password
) {
}
