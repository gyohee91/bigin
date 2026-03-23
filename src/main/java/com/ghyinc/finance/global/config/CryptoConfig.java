package com.ghyinc.finance.global.config;

import com.ghyinc.finance.global.crypto.enums.CryptoAlgorithm;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CryptoConfig {
    private CryptoAlgorithm algorithm;
    private String key;
}
