package com.ghyinc.finance.global.kafka.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class PoisonPillClassifierTest {
    private final PoisonPillClassifier classifier = new PoisonPillClassifier();

    @Test
    @DisplayName("JsonProcessingException → Poison Pill")
    void isPoisonPill_jsonProcessingException_returnsTrue() {
        assertThat(classifier.isPoisonPill(
                new JsonProcessingException("파싱 오류") {}))
                .isTrue();
    }

    @Test
    @DisplayName("IllegalArgumentException → Poison Pill")
    void isPoisonPill_illegalArgumentException_returnsTrue() {
        assertThat(classifier.isPoisonPill(
                new IllegalArgumentException("데이터 없음")))
                .isTrue();
    }

    @Test
    @DisplayName("중첩 예외 내부에 JsonProcessingException → Poison Pill")
    void isPoisonPill_nestedJsonException_returnsTrue() {
        assertThat(classifier.isPoisonPill(
                new JsonProcessingException("중첩 파싱 오류") {}))
                .isTrue();
    }

    @Test
    @DisplayName("ConnectionException → Poison Pill 아님 (일시 장애)")
    void isPoisonPill_connectionException__returnsFalse() {
        assertThat(classifier.isPoisonPill(
                new ConnectException("DB 연결 실패")))
                .isFalse();
    }

    @Test
    @DisplayName("RuntimeException → Poison Pill 아님 (일시 장애)")
    void isPoisonPill_runtimeException_returnsFalse() {
        assertThat(classifier.isPoisonPill(
                new RuntimeException("일시적 오류")))
                .isFalse();
    }

    @Test
    @DisplayName("JsonProcessingException 클래스명 → Poison Pill")
    void isPoisonPillClassName_jsonProcessingException_returnsTrue() {
        assertThat(classifier.isPoisonPillByClassName(
                "com.fasterxml.jackson.core.JsonProcessingException"))
                .isTrue();
    }

    @Test
    @DisplayName("ConnectionException 클래스명 → Poison Pill 아님")
    void isPoisonPillClassName_connectionException_returnsFalse() {
        assertThat(classifier.isPoisonPillByClassName(
                "java.net.ConnectException"))
                .isFalse();
    }
}