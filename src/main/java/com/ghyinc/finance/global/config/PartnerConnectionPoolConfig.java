package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.common.ConnectionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.HttpRoute;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * 파트너사 전체가 공유하는 HTTP 커넥션 풀 구성.
 *
 * <p>기존 SimpleClientHttpRequestFactory(HttpURLConnection)는 커넥션 재사용 한도가
 * JVM 전역 시스템 프로퍼티({@code http.maxConnections}, 기본값 5)로만 제어되고,
 * RestClient 인스턴스별·파트너별로 다르게 줄 방법이 없음.
 * PoolingHttpClientConnectionManager로 전환해 전체 상한(maxTotal)과
 * 파트너별 상한(maxPerRoute)을 분리 관리한다</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PartnerConnectionPoolConfig {
    private final PartnerApiProperties partnerApiProperties;

    /**
     * partnerApiExecutor(max 150 스레드)가 만들어낼 수 있는 최대 동시 호출 수보다
     * 여유를 둔 전체 상한 + 파트너별(HttpRoute) 상한을 함께 등록한다.
     */
    @Bean
    public PoolingHttpClientConnectionManager partnerConnectionManager() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(200);
        connectionManager.setDefaultMaxPerRoute(10);

        ConnectionConfig fallbackConnectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(3000))
                .build();
        Map<HttpRoute, ConnectionConfig> routeConnectionConfigs = new HashMap<>();

        for(Map.Entry<PartnerCode, PartnerApiProperties.PartnerApiConfig> entry : partnerApiProperties.getPartners().entrySet()) {
            PartnerCode partnerCode = entry.getKey();
            PartnerApiProperties.PartnerApiConfig config = entry.getValue();

            // REST가 아닌 파트너는 이 풀을 쓰지 않음.
            if (partnerCode.getConnectionType() != ConnectionType.REST) {
                continue;
            }

            HttpHost host = this.resolveHost(config);
            HttpRoute route = new HttpRoute(host);

            connectionManager.setMaxPerRoute(route, config.getMaxPerRoute());
            routeConnectionConfigs.put(route, ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(config.getConnectTimeoutMs()))
                    .build());
            log.info("[{}] HTTP 커넥션 풀 설정 host={}, maxPerRoute={}", partnerCode, host, config.getMaxPerRoute());
        }

        // nice-api/notification-api처럼 loan-api.partners에 없는 호스트는 fallbackConnectionConfig로 분리
        connectionManager.setConnectionConfigResolver(httpRoute ->
                routeConnectionConfigs.getOrDefault(httpRoute, fallbackConnectionConfig));

        return connectionManager;
    }

    /**
     * 파트너별 CloseableHttpClient
     * 커넥션 풀(connectionManager)은 공유하되, connect/response 타임아웃은 파트너별로 다르게 가져간다.
     * <p>
     * connectionManagerShared(true) 필수 - 이게 없으면 이 클라이언트가 close될 때
     * 다른 파트너와 함께 공유 중인 풀까지 같이 닫혀버린다.
     */
    public CloseableHttpClient buildPartnerHttpClient(
            PoolingHttpClientConnectionManager connectionManager,
            int readTimeoutMs
    ) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                // 풀에 여유 커넥션이 없을 때 무한 대기 대신 빠르게 실패
                // (스레드가 커넥션 기다리며 블로킹되는 것 방지)
                .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionManagerShared(true)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    private HttpHost resolveHost(PartnerApiProperties.PartnerApiConfig config) {
        URI uri = URI.create(config.getBaseUrl());
        int port = config.getPort() > 0 ? config.getPort() : uri.getPort();
        return new HttpHost(uri.getScheme(), uri.getHost(), port);
    }

}
