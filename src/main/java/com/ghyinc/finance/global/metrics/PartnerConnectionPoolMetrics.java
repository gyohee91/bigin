package com.ghyinc.finance.global.metrics;

import com.ghyinc.finance.global.common.ConnectionType;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.pool.PoolStats;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * 파트너사 HTTP 커넥션 풀 (leased/pending/available/max) 상태를 Micrometer로 노출한다.
 * PartnerConnectionPoolConfig 전환이 실제로 병목을 해소했는지 나중에 실측으로 검증하기 위한 지표.
 */
@Component
@RequiredArgsConstructor
public class PartnerConnectionPoolMetrics {
    private final PoolingHttpClientConnectionManager connectionManager;
    private final PartnerApiProperties partnerApiProperties;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void bindGauge() {
        this.registerGaugeSet("total", "ALL", connectionManager::getTotalStats);

        partnerApiProperties.getPartners().forEach((partnerCode, config) -> {
            if (partnerCode.getConnectionType() != ConnectionType.REST) {
                return;     // REST만 이 풀을 씀
            }
            HttpRoute httpRoute = new HttpRoute(this.resolveHost(config));
            this.registerGaugeSet("partner", partnerCode.name(), () -> connectionManager.getStats(httpRoute));
        });
    }

    private void registerGaugeSet(String scope, String partnerTag, Supplier<PoolStats> statsSupplier) {
        this.registerGauge(scope, partnerTag, "leased", statsSupplier, PoolStats::getLeased);
        this.registerGauge(scope, partnerTag, "pending", statsSupplier, PoolStats::getPending);
        this.registerGauge(scope, partnerTag, "available", statsSupplier, PoolStats::getAvailable);
        this.registerGauge(scope, partnerTag, "max", statsSupplier, PoolStats::getMax);
    }

    private void registerGauge(String scope, String partnerTag, String state,
                               Supplier<PoolStats> statsSupplier, ToIntFunction<PoolStats> extractor) {
        Gauge.builder("partner.http.pool.connections", statsSupplier, s -> extractor.applyAsInt(s.get()))
                .tag("scope", scope)
                .tag("partner", partnerTag)
                .tag("state", state)
                .description("파트너사 HTTP 커넥션풀 상태")
                .register(meterRegistry);
    }

    private HttpHost resolveHost(PartnerApiProperties.PartnerApiConfig config) {
        URI uri = URI.create(config.getBaseUrl());
        int port = config.getPort() > 0 ? config.getPort() : uri.getPort();
        return new HttpHost(uri.getScheme(), uri.getHost(), port);
    }
}
