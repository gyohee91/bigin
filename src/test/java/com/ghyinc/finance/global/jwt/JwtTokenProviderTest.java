package com.ghyinc.finance.global.jwt;

import com.ghyinc.finance.domain.user.enums.MemberRole;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class JwtTokenProviderTest {
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        // HS256은 최소 256bit(32byte) 이상 키 필요
        jwtTokenProvider = new JwtTokenProvider("test-secret-key-for-jwt-unit-test-32bytes!!");
    }

    @Test
    @DisplayName("Access Token 생성 시 Claim이 올바르게 담긴다")
    void createAccessToken_shouldContainExpectedClaims() {
        String token = jwtTokenProvider.createAccessToken(1L, MemberRole.USER);

        assertThat(jwtTokenProvider.validationToken(token)).isTrue();

        Claims claims = jwtTokenProvider.parseClaims(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("role")).isEqualTo("USER");
        assertThat(claims.get("tokenType")).isEqualTo("ACCESS");
        assertThat(claims.getId()).isNotBlank();
    }

    @Test
    @DisplayName("Refresh Token 생성 시 tokenType이 REFRESH")
    void createRefreshToken_shouldHaveRefreshTokenType() {
        String token = jwtTokenProvider.createRefreshToken(1L, MemberRole.USER);

        Claims claims = jwtTokenProvider.parseClaims(token);
        assertThat(claims.get("tokenType")).isEqualTo("REFRESH");
    }

    @Test
    @DisplayName("Token이 위조되면 검증에 실패한다")
    void validationToken_shouldReturnFalse_whenTokenIsTampered() {
        String token = jwtTokenProvider.createAccessToken(1L, MemberRole.USER);

        int payloadStart = token.indexOf('.') + 1;
        char original = token.charAt(payloadStart);
        char flipped = (original == 'a') ? 'b' : 'a';
        String tampered = token.substring(0, payloadStart) + flipped + token.substring(payloadStart + 1);

        assertThat(jwtTokenProvider.validationToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("다른 Key로 서명된 Token은 검증에 실패한다")
    void validationToken_shouldReturnFalse_whenSignedWithDifferentKey() {
        JwtTokenProvider otherProvider = new JwtTokenProvider("different-secret-key-32bytes-long!!");
        String token = otherProvider.createAccessToken(1L, MemberRole.USER);

        assertThat(jwtTokenProvider.validationToken(token)).isFalse();
    }
}