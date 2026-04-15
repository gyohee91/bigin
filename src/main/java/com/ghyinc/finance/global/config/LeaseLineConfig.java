package com.ghyinc.finance.global.config;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.client.LeaseLineConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class LeaseLineConfig {
    private final PartnerApiProperties partnerApiProperties;

    @Bean
    public Map<PartnerCode, LeaseLineConnection> leaseLineConnections() {
        return partnerApiProperties.getPartners().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new LeaseLineConnection(e.getValue().getBaseUrl(), e.getValue().getPort())
                ));
    }
}
