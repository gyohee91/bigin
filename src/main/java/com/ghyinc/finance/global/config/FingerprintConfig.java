package com.ghyinc.finance.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * 운영 중 ConfigMap으로 바꾸는 설정(제휴사 호출·Resilience4j)의 지문
 * <p>
 * 같은 버전에서는 모든 Pod가 같은 지문을 가져야 한다. 롤링 업데이트가 끝난 뒤에도 지문이
 * 둘 이상이면 일부 Pod가 이전 설정으로 떠 있다는 뜻이다. 비밀값이 섞이지 않도록 대상 접두사만 해시한다.</p>
 *
 * 환경변수로 주입한 값은 접두사가 달라 대상에 포함되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FingerprintConfig implements InfoContributor {
    private final ConfigurableEnvironment environment;
    private final MeterRegistry meterRegistry;

    private String fingerprint;
    private int propertyCount;

    static final List<String> PREFIXES =
            List.of("resilience4j.", "loan-api.", "nice-api.", "notification-api.");

    @PostConstruct
    void init() {
        SortedMap<String, String> properties = collect();
        this.propertyCount = properties.size();
        this.fingerprint = hash(properties).substring(0, 12);

        Gauge.builder("app.config.info", () -> 1)
                .description("현재 파드의 설정 지문 (값은 항상 1, 지문은 태그)")
                .tag("fingerprint", fingerprint)
                .register(meterRegistry);
        log.info("[설정] fingerprint={} (대상 프로퍼티 {}개)", fingerprint, propertyCount);
    }

    String fingerprint() {
        return fingerprint;
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("config", Map.of("fingerprint", fingerprint, "properties", propertyCount));
    }

    private SortedMap<String, String> collect() {
        SortedMap<String, String> properties = new TreeMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if(!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if(PREFIXES.stream().noneMatch(name::startsWith) || properties.containsKey(name)) {
                    continue;
                }
                try {
                    // 우선순위가 반영된 최종 값을 쓴다. (ConfigMap이 jar 기본 값을 덮어쓴 결과)
                    properties.put(name, String.valueOf(environment.getProperty(name)));
                } catch (IllegalArgumentException e) {
                    properties.put(name, "<unresolved>");
                }
            }
        }
        return properties;
    }

    private static String hash(SortedMap<String, String> properties) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            properties.forEach((key, value) ->
                    digest.update((key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
