package com.ghyinc.finance.global.jwt;

import com.ghyinc.finance.domain.user.enums.MemberRole;
import com.ghyinc.finance.global.jwt.enums.TokenType;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final SecretKey secretKey;

    private static final long ACCESS_TOKEN_EXPIRE_MS = 30 * 60 * 1000L;             // 30분
    private static final long REFRESH_TOKEN_EXPIRE_MS = 14 * 24 * 60 * 60  * 1000L; // 14일

    public JwtTokenProvider(@Value("${jwt.secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long memberId, MemberRole role) {
        return this.createToken(memberId, role, ACCESS_TOKEN_EXPIRE_MS, TokenType.ACCESS);
    }

    public String createRefreshToken(Long memberId, MemberRole role) {
        return this.createToken(memberId, role, REFRESH_TOKEN_EXPIRE_MS, TokenType.REFRESH);
    }

    private String createToken(Long memberId, MemberRole role, long expireMs, TokenType type) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(memberId))     // sub: 내부 PK
                .claim("role", role.name())         // 인가용 권한
                .claim("tokenType", type.name())    // ACCESS/REFRESH 구분
                .id(UUID.randomUUID().toString())      // jti: 로그아웃 블랙리스트/재사용탐지용
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMs))
                .signWith(secretKey)
                .compact();
    }

    public boolean validationToken(String token) {
        try {
            this.parseClaims(token);
            return true;
        } catch (ExpiredJwtException | UnsupportedJwtException | MalformedJwtException | SecurityException e) {
            return false;
        }
    }

    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(secretKey).build()
                .parseSignedClaims(token).getPayload();
    }
}
