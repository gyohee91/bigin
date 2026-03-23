package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.common.ConnectionType;
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
    public static class PartnerApiConfig {
        private String baseUrl;
        private String path;
        private ConnectionType connectionType;
        private CryptoConfig crypto;
    }
}
