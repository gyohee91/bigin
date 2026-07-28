package com.ghyinc.finance.global.exception;

import lombok.Getter;

@Getter
public class ExternalApiServerException extends RuntimeException {
    private final String resultCode;

    public ExternalApiServerException(String resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
