package com.ghyinc.finance.global.crypto.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CryptoAlgorithm {
    AES_256_CBC("AES/CBC/PKCS5Padding"),
    AES_256_ECB("AES/ECB/PKCS5Padding"),
    RSA_OAEP("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");

    private final String algorithm;
}
