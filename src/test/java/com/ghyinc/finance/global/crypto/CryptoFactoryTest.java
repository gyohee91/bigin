package com.ghyinc.finance.global.crypto;

import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import com.ghyinc.finance.global.crypto.impl.AesCryptoService;
import com.ghyinc.finance.global.exception.CryptoException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class CryptoFactoryTest {

    @InjectMocks
    private CryptoFactory cryptoFactory;

    @Mock
    private PartnerRepository partnerRepository;

    @Test
    @DisplayName("AES-256-CBC 파트너 → AesCryptoService 반환")
    void getCryptoService_aesCbc_returnsAesCryptoService() {
        // given
        Partner partner = mock(Partner.class);
        given(partner.getAlgorithm()).willReturn(CryptoAlgorithm.AES_256_CBC);
        given(partner.getCryptoKey()).willReturn(
                Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes())
        );
        given(partnerRepository.findByPartnerCodeAndActive(PartnerCode.KAKAO_BANK, true))
                .willReturn(Optional.of(partner));

        // when
        CryptoService result = cryptoFactory.getCryptoService(PartnerCode.KAKAO_BANK);

        // AesCryptoService 인스턴스 반환 확인
        assertThat(result).isInstanceOf(AesCryptoService.class);
    }

    @Test
    @DisplayName("파트너 정보 없으면 CryptoException 발생")
    void getCryptoService_partnerNotFound_throwsCryptoException() {
        // given
        given(partnerRepository.findByPartnerCodeAndActive(PartnerCode.KAKAO_BANK, true))
                .willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> cryptoFactory.getCryptoService(PartnerCode.KAKAO_BANK))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("파트너 정보 없음");
    }

    @Test
    @DisplayName("암호화 알고리즘 미설정 파트너 → CryptoException 발생")
    void getCryptoService_algorithmNotSet_throwsCryptoException() {
        // given
        Partner partner = mock(Partner.class);
        given(partner.getAlgorithm()).willReturn(null);
        given(partnerRepository.findByPartnerCodeAndActive(PartnerCode.KAKAO_BANK, true))
                .willReturn(Optional.of(partner));

        // when & then
        assertThatThrownBy(() -> cryptoFactory.getCryptoService(PartnerCode.KAKAO_BANK))
                .isInstanceOf(CryptoException.class)
                .hasMessageContaining("암호화 설정 없음");
    }
}