package com.ghyinc.finance.global.crypto.impl;

import com.ghyinc.finance.global.crypto.CryptoService;
import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import com.ghyinc.finance.global.exception.CryptoException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
public class AesCryptoService implements CryptoService {
    private final SecretKeySpec secretKeySpec;
    private final CryptoAlgorithm algorithm;
    private final byte[] iv;        //CBC 모드용 초기화 벡터

    public AesCryptoService(String key, CryptoAlgorithm algorithm) {
        byte[] keyBytes = Base64.getDecoder().decode(key);
        this.secretKeySpec = new SecretKeySpec(keyBytes, "AES");
        this.algorithm = algorithm;
        this.iv = Arrays.copyOf(keyBytes, 16);  //Key 앞 16byte를 IV로 사용
    }

    @Override
    public boolean supports(CryptoAlgorithm algorithm) {
        return algorithm == CryptoAlgorithm.AES_256_CBC ||
                algorithm == CryptoAlgorithm.AES_256_ECB;
    }

    @Override
    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(algorithm.getAlgorithm());

            if(algorithm == CryptoAlgorithm.AES_256_CBC) {
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));
            }
            else {
                cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
            }

            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);

        } catch (Exception e) {
            log.error("AES 암호화 오류", e);
            throw new CryptoException(e.getMessage());
        }
    }

    @Override
    public String decrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(algorithm.getAlgorithm());

            if(algorithm == CryptoAlgorithm.AES_256_CBC) {
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, new IvParameterSpec(iv));
            }
            else {
                cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
            }

            byte[] decrypted = Base64.getDecoder().decode(plainText);
            return new String(cipher.doFinal(decrypted), StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("AES 복호화 오류", e);
            throw new CryptoException(e.getMessage());
        }
    }
}
