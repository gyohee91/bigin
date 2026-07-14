package com.ghyinc.finance.global.crypto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@SpringBootTest
class CryptoFactoryCacheIntegrationTest {

    @Autowired
    private CryptoFactory cryptoFactory;

    @MockitoSpyBean
    private PartnerRepository partnerRepository;

    @Test
    @DisplayName("Caffeine 캐시 히트 시 DB 조회 1번만")
    void getCryptoService_caffeineCacheHit_dbCalledOnce() {
        // when
        cryptoFactory.getCryptoService(PartnerCode.KAKAO_BANK);
        cryptoFactory.getCryptoService(PartnerCode.KAKAO_BANK);

        // then
        then(partnerRepository).should(times(1))
                .findByPartnerCodeAndActive(PartnerCode.KAKAO_BANK, true);
    }
}