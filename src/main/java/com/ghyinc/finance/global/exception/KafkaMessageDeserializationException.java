package com.ghyinc.finance.global.exception;

/**
 * Kafka Consumer가 메시지 payload(JSON) 역직렬화에 실패했을 때 던지는 예외.
 * <p>
 * 재시도해도 결과가 달라지지 않는 Poison Pill이므로 DefaultErrorHandler의
 * notRetryableExceptions에 등록되어 즉시 DLQ로 이동한다.
 */
public class KafkaMessageDeserializationException extends RuntimeException {
    public KafkaMessageDeserializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
