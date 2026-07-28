package com.ghyinc.finance.global.exception;

import lombok.Getter;

@Getter
public class ExternalApiClientException extends RuntimeException {
    private final String resultCode;

    public ExternalApiClientException(String resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
