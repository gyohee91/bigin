package com.ghyinc.finance.global.crypto.impl;

import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class RsaCryptoServiceTest {
    private RsaCryptoService rsaCryptoService;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

        rsaCryptoService = new RsaCryptoService(publicKey, privateKey, CryptoAlgorithm.RSA_OAEP);
    }

    @Test
    @DisplayName("RSA supports()")
    void supports() {
        assertThat(rsaCryptoService.supports(CryptoAlgorithm.RSA_OAEP)).isTrue();
        assertThat(rsaCryptoService.supports(CryptoAlgorithm.AES_256_CBC)).isFalse();
        assertThat(rsaCryptoService.supports(CryptoAlgorithm.AES_256_ECB)).isFalse();
    }

    @Test
    void encrypt() {
        String plainText = "윤교희";

        String encData1 = rsaCryptoService.encrypt(plainText);
        String encData2 = rsaCryptoService.encrypt(plainText);

        // RSA OAEP는 랜덤 패딩이므로 매번 다른 암호문
        assertThat(encData1).isNotEqualTo(encData2);

        // 복호화 결과는 동일
        assertThat(rsaCryptoService.decrypt(encData1)).isEqualTo(plainText);
        assertThat(rsaCryptoService.decrypt(encData2)).isEqualTo(plainText);
    }

    @Test
    void decrypt() {
    }
}