package com.ghyinc.finance.global.client;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RateLimiterConfigTest {
    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    // 라이브러리 기본 값(limitForPeriod=50)이 아니라 application.yaml의 default(40)가 적용됐는지 확인
    // (40 = maxPerRoute의 2~3배 원칙으로 설정된 값. 값이 바뀌면 이 테스트도 함께 갱신할 것)
    @ParameterizedTest
    @DisplayName("파트너별 RateLimiter가 application.yaml의 default 설정을 사용한다")
    @EnumSource(value = PartnerCode.class, names = {"KAKAO_BANK", "TOSS_BANK", "LINE_BANK"})
    void ratelimiter_shouldUseConfiguredLimit_notLibraryDefault(PartnerCode partnerCode) {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(partnerCode.name());

        assertThat(rateLimiter.getRateLimiterConfig().getLimitForPeriod()).isEqualTo(40);
    }

    @Test
    @DisplayName("허용량을 초과하면 실제로 요청이 거부된다")
    void ratelimiter_shouldActuallyRejectCallsBeyondLimit() {
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(PartnerCode.KAKAO_BANK.name());

        for(int i = 0; i < 40; i++) {
            assertThat(rateLimiter.acquirePermission()).isTrue();
        }

        // 같은 1초 주기 안에서 41번째 요청은 거부되어야 한다.
        assertThat(rateLimiter.acquirePermission()).isFalse();
    }
}