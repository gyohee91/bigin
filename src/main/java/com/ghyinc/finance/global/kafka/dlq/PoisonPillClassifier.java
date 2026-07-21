package com.ghyinc.finance.global.kafka.dlq;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Poison Pill 판별
 */
@Component
public class PoisonPillClassifier {
    private static final Set<String> POISON_PILL_CLASS_NAMES = Set.of(
            "com.fasterxml.jackson.core.JsonProcessingException",
            "com.fasterxml.jackson.databind.exc.MismatchedInputException",
            "com.fasterxml.jackson.databind.exc.InvalidFormatException",
            "java.lang.IllegalArgumentException",
            "java.lang.ClassCastException"
    );

    // 재시도해도 의미 없는 예외 → Poison Pill
    public boolean isPoisonPill(Exception cause) {
        return this.isMatchByClassHierarchy(cause.getClass())
                || this.isNestedException(cause);
    }

    /**
     * 예외 클래스명 문자열로 판별 (DlqEventConsumer에서 사용)
     */
    public boolean isPoisonPillByClassName(String exceptionClassName) {
        return POISON_PILL_CLASS_NAMES.contains(exceptionClassName)
                || exceptionClassName.contains("JsonProcessingException")
                || exceptionClassName.contains("MismatchedInputException");
    }

    /**
     * 클래스 자체 + 상위 클래스 순환
     */
    private boolean isMatchByClassHierarchy(Class<?> clazz) {
        while (clazz != null && clazz != Object.class) {
            if(POISON_PILL_CLASS_NAMES.contains(clazz.getName())) {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    /**
     * 중첩 예외 순회
     */
    private boolean isNestedException(Throwable ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if(this.isMatchByClassHierarchy(cause.getClass())) return true;
            cause = cause.getCause();
        }
        return false;
    }
}
