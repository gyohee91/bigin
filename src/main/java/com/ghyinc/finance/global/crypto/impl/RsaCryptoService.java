package com.ghyinc.finance.global.crypto.impl;

import com.ghyinc.finance.global.crypto.CryptoService;
import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;

public class RsaCryptoService implements CryptoService {
    @Override
    public boolean supports(CryptoAlgorithm algorithm) {
        return algorithm == CryptoAlgorithm.RSA_OAEP;
    }

    @Override
    public String encrypt(String plainText) {
        return "";
    }

    @Override
    public String decrypt(String plainText) {
        return "";
    }
}
