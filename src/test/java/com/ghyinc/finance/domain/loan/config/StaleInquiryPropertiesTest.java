package com.ghyinc.finance.domain.loan.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StaleInquiryPropertiesTest {

    private static StaleInquiryProperties of(Duration accepted, Duration pending, Duration inProgress) {
        return new StaleInquiryProperties(accepted, pending, inProgress, 100, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    @Test
    @DisplayName("minTimeout - 기본값(2m, 2m, 5m)에서는 2분")
    void minTimeout_defaults() {
        assertThat(of(Duration.ofMinutes(2), Duration.ofMinutes(2), Duration.ofMinutes(5)).minTimeout())
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("minTimeout - accepted 가 가장 작으면 accepted")
    void minTimeout_acceptedSmallest() {
        assertThat(of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5)).minTimeout())
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("minTimeout - pending 이 가장 작으면 pending")
    void minTimeout_pendingSmallest() {
        assertThat(of(Duration.ofMinutes(4), Duration.ofSeconds(45), Duration.ofMinutes(5)).minTimeout())
                .isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("minTimeout - in-progress 가 가장 작으면 in-progress")
    void minTimeout_inProgressSmallest() {
        assertThat(of(Duration.ofMinutes(4), Duration.ofMinutes(3), Duration.ofSeconds(90)).minTimeout())
                .isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    @DisplayName("minTimeout - 값이 모두 같으면 그 값")
    void minTimeout_allEqual() {
        assertThat(of(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1)).minTimeout())
                .isEqualTo(Duration.ofMinutes(1));
    }
}
