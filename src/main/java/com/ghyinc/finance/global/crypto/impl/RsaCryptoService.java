package com.ghyinc.finance.global.crypto.impl;

import com.ghyinc.finance.global.crypto.CryptoService;
import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import com.ghyinc.finance.global.exception.CryptoException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Slf4j
public class RsaCryptoService implements CryptoService {
    private final PublicKey publicKey;
    private final PrivateKey privateKey;
    private final CryptoAlgorithm algorithm;

    public RsaCryptoService(String publicKeyStr, String privateKeyStr, CryptoAlgorithm algorithm) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            byte[] publicKeyBytes = Base64.getDecoder().decode(publicKeyStr);
            this.publicKey = keyFactory.generatePublic(
                    new X509EncodedKeySpec(publicKeyBytes)
            );

            byte[] privateKeyBytes = Base64.getDecoder().decode(privateKeyStr);
            this.privateKey = keyFactory.generatePrivate(
                    new PKCS8EncodedKeySpec(privateKeyBytes)
            );

            this.algorithm = algorithm;

        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new CryptoException("RSA 키 초기화 오류");
        }
    }

    @Override
    public boolean supports(CryptoAlgorithm algorithm) {
        return algorithm == CryptoAlgorithm.RSA_OAEP;
    }

    @Override
    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(algorithm.getAlgorithm());
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encrypted = cipher.doFinal(
                    plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            log.error("RSA 암호화 오류", e);
            throw new CryptoException("암호화 처리 중 오류 발생");
        }
    }

    @Override
    public String decrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(algorithm.getAlgorithm());
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decrypted = Base64.getDecoder().decode(plainText);
            return new String(cipher.doFinal(decrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("RSA 복호화 오류", e);
            throw new CryptoException("복호화 처리 중 오류 발생");
        }
    }
}
