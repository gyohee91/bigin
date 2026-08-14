package com.ghyinc.finance.global.exception;

public class LockUnavailableException extends RuntimeException {
    public LockUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
