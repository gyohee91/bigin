package com.ghyinc.finance.domain.external.nice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Nice DNR API 설정
 * <p>application.yml 설정 예시</p>
 * <pre>
 * nice-api:
 *   dnr:
 *     base-url: https://api.nice.co.kr
 *     path: /v1/vehicle/registration
 *     api-key: nice-api-key
 *     timeout-ms: 5000
 * </pre>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nice-api")
public class NiceApiProperties {
    private NiceApiConfig dnr;

    @Getter
    @Setter
    public static class NiceApiConfig {
        private String baseUrl;
        private String path;
    }
}
