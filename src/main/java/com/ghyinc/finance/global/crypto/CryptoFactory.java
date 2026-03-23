package com.ghyinc.finance.global.crypto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.config.CryptoConfig;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import com.ghyinc.finance.global.crypto.impl.AesCryptoService;
import com.ghyinc.finance.global.crypto.impl.RsaCryptoService;
import com.ghyinc.finance.global.exception.CryptoException;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class CryptoFactory {
    private final Map<PartnerCode, CryptoService> cryptoServiceMap;

    // 금융사별 CryptoService 선택
    public CryptoFactory(PartnerApiProperties partnerApiProperties) {
        this.cryptoServiceMap = partnerApiProperties.getPartners().entrySet().stream()
                .filter(e -> e.getValue().getCrypto() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> this.buildCryptoService(e.getValue().getCrypto())
                ));
    }

    public CryptoService getCryptoService(PartnerCode partnerCode) {
        return Optional.ofNullable(cryptoServiceMap.get(partnerCode))
                .orElseThrow(() -> new CryptoException(partnerCode + " 암호화 설정 없음"));
    }

    private CryptoService buildCryptoService(CryptoConfig crypto) {
        return switch (crypto.getAlgorithm()) {
            case AES_256_CBC, AES_256_ECB -> new AesCryptoService(crypto.getKey(), crypto.getAlgorithm());
            case RSA_OAEP -> new RsaCryptoService();
        };
    }

}
