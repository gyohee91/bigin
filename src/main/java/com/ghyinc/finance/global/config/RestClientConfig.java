package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(PartnerApiProperties.class)
public class RestClientConfig {
    private final PartnerApiProperties partnerApiProperties;

    /**
     * 금융사별 전용 RestClient Map
     *
     * <p>PartnerCode를 Key로 각 금융사의 baseUrl 등이 세팅된
     * RestClient를 미리 생성해두고 Adaptor에서 주입받아 사용
     * 금융사 추가 시 yml 설정만 추가하면 자동으로 RestClient가 생성됨.
     */
    @Bean
    public Map<PartnerCode, RestClient> partnerRestClients() {
        return partnerApiProperties.getPartners().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> this.buildRestClient(entry.getKey(), entry.getValue())
                ));
    }

    private RestClient buildRestClient(PartnerCode partnerCode, PartnerApiProperties.PartnerApiConfig config) {
        return RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
