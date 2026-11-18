package com.ghyinc.finance.global.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FingerprintConfigTest {

    private FingerprintConfig create(MockEnvironment env, SimpleMeterRegistry registry) {
        FingerprintConfig fingerprint = new FingerprintConfig(env, registry);
        fingerprint.init();
        return fingerprint;
    }

    private FingerprintConfig create(MockEnvironment env) {
        return create(env, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("같은 설정이면 선언 순서와 무관하게 같은 지문")
    void sameProperties_sameFingerprint_regardlessOfOrder() {
        MockEnvironment a = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20")
                .withProperty("loan-api.KAKAO_BANK.base-url", "http://a");
        MockEnvironment b = new MockEnvironment()
                .withProperty("loan-api.KAKAO_BANK.base-url", "http://a")
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20");

        assertThat(create(a).fingerprint()).isEqualTo(create(b).fingerprint());
    }

    @Test
    @DisplayName("대상 프로퍼티 값이 바뀌면 지문이 달라진다")
    void changedValue_changesFingerprint() {
        MockEnvironment before = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20");
        MockEnvironment after = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "30");

        assertThat(create(before).fingerprint()).isNotEqualTo(create(after).fingerprint());
    }

    @Test
    @DisplayName("대상 프로퍼티가 추가되면 지문이 달라진다")
    void addedProperty_changesFingerprint() {
        MockEnvironment before = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20");
        MockEnvironment after = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20")
                .withProperty("resilience4j.bulkhead.instances.X.base-config", "default");

        assertThat(create(before).fingerprint()).isNotEqualTo(create(after).fingerprint());
    }

    @Test
    @DisplayName("대상이 아닌 프로퍼티(비밀값 등)는 지문에 영향을 주지 않는다")
    void unrelatedProperties_areIgnored() {
        MockEnvironment a = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20")
                .withProperty("jwt.secret", "secret-a");
        MockEnvironment b = new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20")
                .withProperty("jwt.secret", "secret-b");

        assertThat(create(a).fingerprint()).isEqualTo(create(b).fingerprint());
    }

    @Test
    @DisplayName("지문은 /actuator/info 의 config 항목으로 노출된다")
    void info_containsFingerprint() {
        FingerprintConfig fingerprint = create(new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20"));
        Info.Builder builder = new Info.Builder();

        fingerprint.contribute(builder);

        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) builder.build().getDetails().get("config");
        assertThat(config).containsEntry("fingerprint", fingerprint.fingerprint());
        assertThat(config).containsEntry("properties", 1);
    }

    @Test
    @DisplayName("지문이 태그로 달린 Gauge 가 지표 레지스트리에 등록된다")
    void gauge_isRegisteredWithFingerprintTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FingerprintConfig fingerprint = create(new MockEnvironment()
                .withProperty("resilience4j.bulkhead.configs.default.max-concurrent-calls", "20"), registry);

        assertThat(registry.find("app.config.info").tag("fingerprint", fingerprint.fingerprint()).gauge())
                .isNotNull();
    }
}