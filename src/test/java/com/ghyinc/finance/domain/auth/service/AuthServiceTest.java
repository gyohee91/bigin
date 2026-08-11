package com.ghyinc.finance.domain.auth.service;

import com.ghyinc.finance.domain.auth.dto.TokenResponse;
import com.ghyinc.finance.domain.auth.security.CustomUserDetails;
import com.ghyinc.finance.domain.user.entity.Member;
import com.ghyinc.finance.domain.user.enums.MemberRole;
import com.ghyinc.finance.domain.user.repository.MemberRepository;
import com.ghyinc.finance.global.exception.AuthenticationFailedException;
import com.ghyinc.finance.global.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @InjectMocks
    private AuthService authService;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private RBucket<String> refreshBucket;

    @Mock
    private RBucket<Boolean> blacklistBucket;


    @Test
    @DisplayName("로그인 성공 시 Token을 발급하고 refreshToken을 Redis에 저장한다")
    void login_shouldIssueTokensAndStoreRefreshTokenInRedis() {
        // given
        Member member = Member.builder()
                .userId(1L)
                .mobile("01012341234")
                .role(MemberRole.USER)
                .build();
        CustomUserDetails userDetails = new CustomUserDetails(member);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());

        given(authenticationManager.authenticate(any())).willReturn(authentication);
        given(jwtTokenProvider.createAccessToken(1L, MemberRole.USER)).willReturn("access-token");
        given(jwtTokenProvider.createRefreshToken(1L, MemberRole.USER)).willReturn("refresh-token");
        given(redissonClient.<String>getBucket("auth:refresh:1")).willReturn(refreshBucket);

        // when
        TokenResponse response = authService.login("01012341234", "password");

        // then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        then(refreshBucket).should().set(eq("refresh-token"), eq(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("비밀번호 불일치시 AuthenticationFailedException이 발생한다")
    void login_shouldThrowAuthenticationFailedException_whenCredentialsAreInvalid() {
        // given
        given(authenticationManager.authenticate(any())).willThrow(new BadCredentialsException("bad"));

        // when & then
        assertThatThrownBy(() -> authService.login("01012341234", "wrong"))
                .isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("정상 refresh 요청 시 새 Token 쌍을 발급한다")
    void refresh_shouldIssueNewTokenPair_whenTokenIsValid() {
        // given
        given(jwtTokenProvider.validationToken("refresh-token")).willReturn(true);

        Claims claims = mock(Claims.class);
        given(claims.get("tokenType")).willReturn("REFRESH");
        given(claims.getSubject()).willReturn("1");
        given(jwtTokenProvider.parseClaims("refresh-token")).willReturn(claims);

        given(redissonClient.<String>getBucket("auth:refresh:1")).willReturn(refreshBucket);
        given(refreshBucket.get()).willReturn("refresh-token");     // 저장된 값과 일치

        Member member = Member.builder()
                .userId(1L)
                .mobile("01012341234")
                .role(MemberRole.USER)
                .build();
        given(memberRepository.findById(1L)).willReturn(Optional.of(member));
        given(jwtTokenProvider.createAccessToken(1L, MemberRole.USER)).willReturn("new-access");
        given(jwtTokenProvider.createRefreshToken(1L, MemberRole.USER)).willReturn("new-refresh");

        // when
        TokenResponse response = authService.refresh("refresh-token");

        // then
        assertThat(response.accessToken()).isEqualTo("new-access");
        then(refreshBucket).should().set(eq("new-refresh"), eq(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("저장된 토큰과 다르면 재사용으로 간주하고 예외 발생 및 Redis 삭제한다")
    void refresh_shouldThrowAndDeleteToken_whenTokenReuseIsDetected() {
        // given
        given(jwtTokenProvider.validationToken("refresh-token")).willReturn(true);

        Claims claims = mock(Claims.class);
        given(claims.get("tokenType")).willReturn("REFRESH");
        given(claims.getSubject()).willReturn("1");
        given(jwtTokenProvider.parseClaims("refresh-token")).willReturn(claims);

        given(redissonClient.<String>getBucket("auth:refresh:1")).willReturn(refreshBucket);
        given(refreshBucket.get()).willReturn("old-different-token");

        // when & then
        assertThatThrownBy(() -> authService.refresh("refresh-token"))
                .isInstanceOf(AuthenticationFailedException.class);

        then(refreshBucket).should().delete();
    }

    @Test
    @DisplayName("로그아웃 시 Refresh Token 삭제하고 Access Token을 블랙리스트에 등록한다")
    void logout_shouldDeleteRefreshTokenAndBlacklistAccessToken() {
        // given
        given(redissonClient.<String>getBucket("auth:refresh:1")).willReturn(refreshBucket);

        Claims claims = mock(Claims.class);
        given(claims.getId()).willReturn("jti-123");
        given(claims.getExpiration()).willReturn(new Date(System.currentTimeMillis() + 60_000));
        given(jwtTokenProvider.parseClaims("access-token")).willReturn(claims);

        given(redissonClient.<Boolean>getBucket("auth:blacklist:jti-123")).willReturn(blacklistBucket);

        // when
        authService.logout(1L, "access-token");

        // then
        then(refreshBucket).should().delete();
        then(blacklistBucket).should().set(eq(true), any(Duration.class));
    }
}