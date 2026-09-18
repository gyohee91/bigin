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

    public static final long CONNECTION_REQUEST_TIMEOUT_MS = 500;

    /**
     * partnerApiExecutor(max 300 스레드, 50개 파트너 동시 팬아웃 기준 재산정)가 만들어낼 수 있는
     * 최대 동시 호출 수보다 여유를 둔 전체 상한 + 파트너별(HttpRoute) 상한을 함께 등록한다.
     * <p>
     * 목표 20 req/s × 최대 49개 REST 파트너 팬아웃 × 응답시간 ~300ms 가정 시
     * Little's Law로 필요한 동시 커넥션 수 ≈ 20 × 49 × 0.3 ≈ 294 - maxTotal 400이면 여유 있음.
     */
    @Bean
    public PoolingHttpClientConnectionManager partnerConnectionManager() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        int maxTotal = 400;
        connectionManager.setMaxTotal(maxTotal);
        connectionManager.setDefaultMaxPerRoute(10);

        ConnectionConfig fallbackConnectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(1000))
                .build();
        Map<HttpRoute, ConnectionConfig> routeConnectionConfigs = new HashMap<>();

        // HttpRoute의 동등성은 scheme/host/port 기준이다. 로컬 부하테스트처럼 여러 PartnerCode가
        // 물리적으로 같은 호스트(예: 전부 http://localhost:8091)를 공유하면 서로 다른 파트너라도
        // 같은 HttpRoute로 충돌한다. 예전엔 setMaxPerRoute(route, n)을 파트너 순회마다 그냥 호출해서
        // 마지막에 처리된 파트너의 maxPerRoute로 나머지 전부가 덮어써졌다 - 그래서 49개 파트너가
        // 공유하는 라우트인데도 실제 허용 커넥션 수는 15~20개뿐이었고, 나머지는 커넥션을 못 빌려서
        // DeadlineTimeoutException으로 계속 실패했다. 충돌 시 개별 상한을 "덮어쓰기"가 아니라
        // "합산"해서 해당 라우트가 실제로 필요로 하는 총 동시 커넥션 수를 반영하고, maxTotal을 넘지
        // 않도록 캡을 씌운다.
        Map<HttpRoute, Integer> maxPerRouteSum = new HashMap<>();

        for(Map.Entry<PartnerCode, PartnerApiProperties.PartnerApiConfig> entry : partnerApiProperties.getPartners().entrySet()) {
            PartnerCode partnerCode = entry.getKey();
            PartnerApiProperties.PartnerApiConfig config = entry.getValue();

            // REST가 아닌 파트너는 이 풀을 쓰지 않음.
            if (partnerCode.getConnectionType() != ConnectionType.REST) {
                continue;
            }

            HttpHost host = this.resolveHost(config);
            HttpRoute route = new HttpRoute(host);

            int aggregatedMaxPerRoute = maxPerRouteSum.merge(route, config.getMaxPerRoute(), Integer::sum);
            int cappedMaxPerRoute = Math.min(aggregatedMaxPerRoute, maxTotal);
            connectionManager.setMaxPerRoute(route, cappedMaxPerRoute);

            // 같은 라우트를 공유하는 파트너끼리 connectTimeoutMs가 다르면 마지막 값으로 덮어써진다.
            // 현재는 전부 동일한 로컬 mock 호스트를 쓰므로 실질적 영향은 없지만, 서로 다른 실제
            // 호스트를 쓰게 되면(=충돌이 안 생기면) 이 이슈 자체가 발생하지 않는다.
            routeConnectionConfigs.put(route, ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(config.getConnectTimeoutMs()))
                    .build());
            log.info("[{}] HTTP 커넥션 풀 설정 host={}, maxPerRoute(라우트 합산 후 캡 적용)={}",
                    partnerCode, host, cappedMaxPerRoute);
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
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECTION_REQUEST_TIMEOUT_MS))
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
