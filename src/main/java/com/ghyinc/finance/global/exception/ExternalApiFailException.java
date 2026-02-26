package com.ghyinc.finance.global.exception;

import lombok.Getter;

@Getter
public class ExternalApiFailException extends RuntimeException {
    private final String resultCode;

    public ExternalApiFailException(String resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

}
