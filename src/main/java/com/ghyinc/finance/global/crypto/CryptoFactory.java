package com.ghyinc.finance.global.crypto;

import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.global.config.CryptoConfig;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import com.ghyinc.finance.global.crypto.impl.AesCryptoService;
import com.ghyinc.finance.global.crypto.impl.RsaCryptoService;
import com.ghyinc.finance.global.exception.CryptoException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoFactory {
    private final PartnerRepository partnerRepository;

    @Cacheable(value = "cryptoService", key = "#partnerCode")
    public CryptoService getCryptoService(PartnerCode partnerCode) {
        Partner partner = partnerRepository.findByPartnerCodeAndActive(partnerCode, true)
                .orElseThrow(() -> new CryptoException(partnerCode + " 파트너 정보 없음"));

        if(Objects.isNull(partner.getAlgorithm())) {
            throw new CryptoException(partnerCode + " 암호화 설정 없음");
        }

        return this.buildCryptoService(partner);
    }

    // 키 교체 시 캐시 초기화 - 관리자 API 또는 Partner 업데이트 시 호출
    @CacheEvict(value = "cryptoService", key = "#partnerCode")
    public void evictCryptoService(PartnerCode partnerCode) {
        log.info("[{}] 암호화 설정 캐시 초기화", partnerCode);
    }

    // 전체 캐시 초기화
    @CacheEvict(value = "cryptoService", allEntries = true)
    public void evictAllCryptoService() {
        log.info("전체 암호화 설정 캐시 초기화");
    }

    private CryptoService buildCryptoService(Partner partner) {
        return switch (partner.getAlgorithm()) {
            case AES_256_CBC, AES_256_ECB -> new AesCryptoService(partner.getCryptoKey(), partner.getAlgorithm());
            case RSA_OAEP -> new RsaCryptoService(partner.getPublicKey(), partner.getPrivateKey(), partner.getAlgorithm());
        };
    }

}
