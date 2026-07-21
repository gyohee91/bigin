package com.ghyinc.finance.global.kafka.dlq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PoisonPillClassifierTest {
    private final PoisonPillClassifier classifier = new PoisonPillClassifier();

    @Test
    @DisplayName("JsonProcessingException → Poison Pill")
    void isPoisonPillByClassName_jsonProcessingException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "com.fasterxml.jackson.core.JsonProcessingException"))
                .isTrue();
    }

    @Test
    @DisplayName("MismatchedInputException → Poison Pill")
    void isPoisonPillByClassName_mismatchedInputException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "com.fasterxml.jackson.databind.exc.MismatchedInputException"))
                .isTrue();
    }

    @Test
    @DisplayName("InvalidFormatException → Poison Pill")
    void isPoisonPillByClassName_invalidFormatException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "com.fasterxml.jackson.databind.exc.InvalidFormatException"))
                .isTrue();
    }

    @Test
    @DisplayName("IllegalArgumentException → Poison Pill")
    void isPoisonPillByClassName_illegalArgumentException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "java.lang.IllegalArgumentException"))
                .isTrue();
    }

    @Test
    @DisplayName("ClassCastException → Poison Pill")
    void isPoisonPillByClassName_classCastException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "java.lang.ClassCastException"))
                .isTrue();
    }

    @Test
    @DisplayName("JsonProcessingException 하위 클래스명 → contains 매칭으로 Poison Pill")
    void isPoisonPillByClassName_subclassOfJsonProcessingException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "com.example.CustomJsonProcessingException"))
                .isTrue();
    }

    @Test
    @DisplayName("ConnectException → Poison Pill 아님 (일시 장애)")
    void isPoisonPillByClassName_connectException_returnsFalse() {
        assertThat(classifier.isPoisonPillByClassName(
                "java.net.ConnectException"))
                .isFalse();
    }

    @Test
    @DisplayName("RuntimeException → Poison Pill 아님 (일시 장애)")
    void isPoisonPillByClassName_runtimeException_returnsFalse() {
        assertThat(classifier.isPoisonPillByClassName(
                "java.lang.RuntimeException"))
                .isFalse();
    }
}
