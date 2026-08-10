package com.ghyinc.finance.domain.auth.service;

import com.ghyinc.finance.domain.auth.dto.TokenResponse;
import com.ghyinc.finance.domain.auth.security.CustomUserDetails;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import com.ghyinc.finance.global.exception.AuthenticationFailedException;
import com.ghyinc.finance.global.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final JwtTokenProvider jwtTokenProvider;
    private final MemberRepository memberRepository;

    private final RedissonClient redissonClient;
    private final AuthenticationManager authenticationManager;

    private static final String REFRESH_KEY_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_KEY_PREFIX = "auth:blacklist:";

    public TokenResponse login(String mobile, String password) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(mobile, password)
            );

            CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
            return this.issueTokens(userDetails.getMember());
        } catch (AuthenticationException e) {
            throw new AuthenticationFailedException("ID 또는 password가 일치하지 않습니다");
        }
    }

    public TokenResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validationToken(refreshToken))
            throw new AuthenticationFailedException("유효하지 않은 토큰입니다");

        Claims claims = jwtTokenProvider.parseClaims(refreshToken);
        if (!"REFRESH".equals(claims.get("tokenType")))
            throw new AuthenticationFailedException("Refresh Token이 아닙니다");

        Long userId = Long.valueOf(claims.getSubject());
        String saved = redissonClient.<String>getBucket(REFRESH_KEY_PREFIX + userId).get();

        // 저장된 값과 다르면 = 이미 사용됐거나 탈취된 토큰 -> 재사용 탐지, 전체 세션 무효화
        if (saved == null || !saved.equals(refreshToken)) {
            redissonClient.getBucket(REFRESH_KEY_PREFIX + userId).delete();
            throw new AuthenticationFailedException("재사용이 감지되었습니다. 다시 로그인해주세요");
        }

        Member member = memberRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailedException("존재하지 않는 계정입니다"));

        return this.issueTokens(member);    // 새 Access + Refresh 발급 (회전)
    }

    public void logout(Long userId, String accessToken) {
        redissonClient.getBucket(REFRESH_KEY_PREFIX + userId).delete();

        Claims claims = jwtTokenProvider.parseClaims(accessToken);
        long remainMs = claims.getExpiration().getTime() - System.currentTimeMillis();
        if (remainMs > 0) {
            redissonClient.getBucket(BLACKLIST_KEY_PREFIX + claims.getId())
                    .set(true, Duration.ofMillis(remainMs));    // 남은 유효기간만큼 블랙리스트 유지
        }
    }

    private TokenResponse issueTokens(Member member) {
        String accessToken = jwtTokenProvider.createAccessToken(member.getUserId(), member.getRole());
        String refreshToken = jwtTokenProvider.createRefreshToken(member.getUserId(), member.getRole());

        redissonClient.getBucket(REFRESH_KEY_PREFIX + member.getUserId())
                .set(refreshToken, Duration.ofDays(14));

        return TokenResponse.of(accessToken, refreshToken);
    }
}
