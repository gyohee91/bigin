package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "loan-api")
public class PartnerApiProperties {
    private Map<PartnerCode, PartnerApiConfig> partners;

    public PartnerApiConfig getConfig(PartnerCode partnerCode) {
        PartnerApiConfig config = partners.get(partnerCode);
        if(Objects.isNull(config)) {
            throw new IllegalStateException("금융사 API 설정이 없습니다: " + partnerCode);
        }
        return config;
    }

    @Getter
    @Setter
    private static class PartnerApiConfig {
        private String baseUrl;
        private String path;
    }
}
