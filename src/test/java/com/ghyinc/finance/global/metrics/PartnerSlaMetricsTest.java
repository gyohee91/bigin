package com.ghyinc.finance.global.metrics;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PartnerSlaMetricsTest {
    private SimpleMeterRegistry meterRegistry;
    private PartnerSlaMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new PartnerSlaMetrics(meterRegistry);
    }

    @Test
    @DisplayName("성공 응답은 partner/result=success 태그로 카운터와 타이머를 기록한다")
    void recordTransmission_success() {
        metrics.recordTransmission(LoanLimitAdaptorResponse.success(PartnerCode.TOSS_BANK, 120L));

        assertThat(meterRegistry.get("partner.transmission.count")
                .tag("partner", "TOSS_BANK").tag("result", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.get("partner.transmission.duration")
                .tag("partner", "TOSS_BANK").tag("result", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
    }

    @Test
    @DisplayName("실패 응답은 result=fail 태그로 별도 집계된다")
    void recordTransmission_fail() {
        metrics.recordTransmission(LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, "TIMEOUT", 7000L));

        assertThat(meterRegistry.get("partner.transmission.count")
                .tag("partner", "TOSS_BANK").tag("result", "fail").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 partner+result 조합이 반복되면 카운터가 누적된다")
    void recordTransmission_accumulates() {
        metrics.recordTransmission(LoanLimitAdaptorResponse.success(PartnerCode.TOSS_BANK, 100L));
        metrics.recordTransmission(LoanLimitAdaptorResponse.success(PartnerCode.TOSS_BANK, 100L));

        assertThat(meterRegistry.get("partner.transmission.count")
                .tag("partner", "TOSS_BANK").tag("result", "success").counter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("지표 기록에 실패해도 예외를 전파하지 않는다")
    void recordTransmission_neverThrows() {
        assertThatCode(() -> metrics.recordTransmission(null)).doesNotThrowAnyException();
    }
}
