package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.readmodel.PollingMetrics.Path;
import com.ghyinc.finance.domain.loan.readmodel.PollingMetrics.Outcome;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PollingMetricsTest {
    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    PollingMetrics metrics = new PollingMetrics(meterRegistry);

    @Test
    @DisplayName("생성 시 모든 path 타이머와 결과 카운터가 0 으로 등록된다 (이름/태그는 기존 대시보드와 동일)")
    void registersAllMetersUpFront() {
        for (String path : new String[]{"db", "redis", "unchanged", "rebuild", "fallback_down", "fallback_seed"}) {
            assertThat(meterRegistry.get("polling.service").tag("path", path).timer().count()).isZero();
        }
        for (String result : new String[]{"fallback", "read_repair", "parse_fail"}) {
            assertThat(meterRegistry.get("polling.readmodel").tag("result", result).counter().count()).isZero();
        }
        assertThat(meterRegistry.get("polling.readmodel.parse").timer().count()).isZero();
    }

    @Test
    @DisplayName("stop 은 지정한 path 타이머에만 기록한다")
    void stop_recordsOnlyGivenPath() {
        Timer.Sample sample = metrics.start();
        metrics.stop(sample, Path.UNCHANGED);

        assertThat(meterRegistry.get("polling.service")
                .tag("path", "unchanged")
                .timer().count()
        ).isEqualTo(1L);
        assertThat(meterRegistry.get("polling.service")
                .tag("path", "redis")
                .timer().count()
        ).isZero();
    }

    @Test
    @DisplayName("count 는 해당 결과 카운터만 올린다")
    void count_incrementsGivenOutcome() {
        metrics.count(Outcome.FALLBACK);
        metrics.count(Outcome.FALLBACK);

        assertThat(meterRegistry.get("polling.readmodel")
                .tag("result", "fallback")
                .counter().count()
        ).isEqualTo(2.0);
        assertThat(meterRegistry.get("polling.readmodel")
                .tag("result", "read_repair")
                .counter().count()
        ).isZero();
    }

    @Test
    @DisplayName("파싱 타이머 기록")
    void stopParse_records() {
        metrics.stopParse(metrics.start());

        assertThat(meterRegistry.get("polling.readmodel.parse")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("readmodel result 카운터 11종이 모두 0으로 등록된다")
    void registersAllOutcomes() {
        for (String result : new String[]{"hit", "unchanged", "miss", "stale", "read_fail",
                "write_ok", "write_fail", "write_skipped_no_meta", "fallback", "read_repair", "parse_fail"}) {
            assertThat(meterRegistry.get("polling.readmodel").tag("result", result).counter().count()).isZero();
        }
    }

    @Test
    @DisplayName("redis 호출 타이머는 op/outcome 별로 기록된다")
    void stopRedis_recordsByOpAndOutcome() {
        metrics.stopRedis(metrics.start(), PollingMetrics.RedisOp.READ, true);
        metrics.stopRedis(metrics.start(), PollingMetrics.RedisOp.WRITE, false);

        assertThat(meterRegistry.get("polling.readmodel.redis").tag("op", "read").tag("outcome", "ok").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("polling.readmodel.redis").tag("op", "write").tag("outcome", "error").timer().count()).isEqualTo(1L);
        assertThat(meterRegistry.get("polling.readmodel.redis").tag("op", "read").tag("outcome", "error").timer().count()).isZero();
    }
}