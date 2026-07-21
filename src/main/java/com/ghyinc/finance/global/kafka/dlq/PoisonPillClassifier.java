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

    public boolean isPoisonPillByClassName(String exceptionClassName) {
        return POISON_PILL_CLASS_NAMES.contains(exceptionClassName)
                || exceptionClassName.contains("JsonProcessingException")
                || exceptionClassName.contains("MismatchedInputException");
    }
}