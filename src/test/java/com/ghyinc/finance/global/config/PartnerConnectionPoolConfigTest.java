package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PartnerConnectionPoolConfigTest {
    private PartnerConnectionPoolConfig partnerConnectionPoolConfig;

    @BeforeEach
    void setUp() {
        PartnerApiProperties.PartnerApiConfig kakaobank = new PartnerApiProperties.PartnerApiConfig();
        kakaobank.setBaseUrl("http://kakaobank-mock:8091");
        kakaobank.setConnectTimeoutMs(3000);
        // maxPerRoute 미설정 -> PartnerApiConfig 기본값(10) 검증용

        PartnerApiProperties.PartnerApiConfig kbCapital = new PartnerApiProperties.PartnerApiConfig();
        kbCapital.setBaseUrl("http://kb-capital-mock:8091");
        kbCapital.setConnectTimeoutMs(3000);
        kbCapital.setMaxPerRoute(20);

        PartnerApiProperties.PartnerApiConfig shinhanbank = new PartnerApiProperties.PartnerApiConfig();
        shinhanbank.setBaseUrl("127.0.0.1");
        shinhanbank.setPort(9001);

        PartnerApiProperties partnerApiProperties = new PartnerApiProperties();
        partnerApiProperties.setPartners(
                Map.of(
                        PartnerCode.KAKAO_BANK, kakaobank,
                        PartnerCode.KB_CAPITAL, kbCapital,
                        PartnerCode.SHINHAN_BANK, shinhanbank
                )
        );

        partnerConnectionPoolConfig = new PartnerConnectionPoolConfig(partnerApiProperties);
    }

    @Test
    @DisplayName("LEASE_LINE 파트너(base-url에 스킴 없음)가 섞여 있어도 Bean 생성이 실패하지 않는다")
    void partnerConnectionManager_shouldNotThrow_evenWithLeaseLinePartner() {
        // SHINHAN_BANK의 base-url("127.0.0.1")은 스킴이 없어 URI.create()로 host를 못 뽑는다.
        // ConnectionType.REST만 걸러내는 필터가 없으면 여기서 예외가 난다 (실제 기동 크래시 재현).
        assertThatCode(() -> partnerConnectionPoolConfig.partnerConnectionManager())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("max-per-route를 명시한 파트너는 설정값 그대로 반영된다")
    void partnerConnectionManager_shouldUseConfigurationValue_whenExplicitlySet() {
        PoolingHttpClientConnectionManager connectionManager = partnerConnectionPoolConfig.partnerConnectionManager();
        HttpRoute httpRoute = new HttpRoute(new HttpHost("http", "kb-capital-mock", 8091));

        assertThat(connectionManager.getMaxPerRoute(httpRoute)).isEqualTo(20);
    }

    @Test
    @DisplayName("max-per-route를 명시하지 않은 REST 파트너는 기본값(10)이 적용된다")
    void maxPerRoute_shouldFallBackToDefault_whenNotConfigured() {
        PoolingHttpClientConnectionManager connectionManager = partnerConnectionPoolConfig.partnerConnectionManager();
        HttpRoute httpRoute = new HttpRoute(new HttpHost("http", "kakaobank-mock", 8091));

        assertThat(connectionManager.getMaxPerRoute(httpRoute)).isEqualTo(10);
    }
}