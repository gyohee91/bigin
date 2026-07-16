package com.ghyinc.finance.global.kafka.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Component;

/**
 * Poison Pill 판별
 */
@Component
public class PoisonPillClassifier {

    // 재시도해도 의미 없는 예외 → Poison Pill
    public boolean isPoisonPill(Exception cause) {
        return cause instanceof JsonProcessingException         // 페이로드 파싱 오류
                || cause instanceof IllegalArgumentException    // 데이터 없음
                || cause instanceof ClassCastException          // 타입 불일치
                || this.isNestedException(cause, JsonProcessingException.class);
    }

    private boolean isNestedException(Throwable ex, Class<?> targetType) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if(targetType.isInstance(cause)) return true;
            cause = cause.getCause();
        }
        return false;
    }
}
