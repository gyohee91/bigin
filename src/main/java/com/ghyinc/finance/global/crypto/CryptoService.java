package com.ghyinc.finance.global.crypto;

import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;

public interface CryptoService {
    boolean supports(CryptoAlgorithm algorithm);
    String encrypt(String plainText);
    String decrypt(String plainText);
}
