package com.ghyinc.finance.global.exception;

public class OutboxEventSerializationException extends RuntimeException {
    public OutboxEventSerializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
