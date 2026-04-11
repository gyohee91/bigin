package com.ghyinc.finance.global.crypto.impl;

import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AesCryptoServiceTest {

    private AesCryptoService aesCbcService;
    private AesCryptoService aesEcbService;

    // AES-256 테스트용 키 (32바이트 = 256bit)
    private static final String TEST_KEY =
            Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes());

    @BeforeEach
    void setUp() {
        aesCbcService = new AesCryptoService(TEST_KEY, CryptoAlgorithm.AES_256_CBC);
        aesEcbService = new AesCryptoService(TEST_KEY, CryptoAlgorithm.AES_256_ECB);
    }

    @Test
    @DisplayName("supports() CBC/ECB 시에만 true 반혼")
    void supports() {
        assertThat(aesCbcService.supports(CryptoAlgorithm.AES_256_CBC)).isTrue();
        assertThat(aesCbcService.supports(CryptoAlgorithm.AES_256_ECB)).isTrue();
        assertThat(aesCbcService.supports(CryptoAlgorithm.RSA_OAEP)).isFalse();
    }

    @Test
    @DisplayName("AES-256-CBC: 암호화 후 복호화 시 원문 일치")
    void aesCbc_encrypt() {
        String rrn = "9102131234557";

        String encrypted = aesCbcService.encrypt(rrn);
        String decrypted = aesCbcService.decrypt(encrypted);

        assertThat(encrypted).isNotEqualTo(rrn);
        assertThat(decrypted).isEqualTo(rrn);
    }

    @Test
    @DisplayName("AES-256-ECB: 암호화 후 복호화 시 원문 일치")
    void aesEcb_encrypt() {
        String rrn = "9102131234567";

        String encrypted = aesEcbService.encrypt(rrn);
        String decrypted = aesEcbService.decrypt(encrypted);

        assertThat(encrypted).isNotEqualTo(rrn);
        assertThat(decrypted).isEqualTo(rrn);
    }

    @Test
    void decrypt() {
    }
}