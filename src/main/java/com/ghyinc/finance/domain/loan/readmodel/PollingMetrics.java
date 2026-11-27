package com.ghyinc.finance.domain.loan.readmodel;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 폴링 API 매트릭 모음. 미터를 생성 시 한 번만 등록해 호출마다 빌더/조회 비용을 없애고,
 * 문자열 대신 enum으로만 기록하게 해 오타를 막는다.
 * <ul>
 *     <li>{@code polling.service{path=...}}: 응답 경로별 처리 시간</li>
 *     <li>{@code polling.readmodel{result=...}}: 결과 카운터 (hit/miss/write_* 등은 Store가 기록)</li>
 *     <li>{@code polling.readmodel.parse}: 항목 JSON 파싱 시간</li>
 * </ul>
 */
@Component
public class PollingMetrics {
    /**
     * polling.service 의 path 태그. 값은 기존 문자열과 같게 유지(대시보드 호환)
     */
    public enum Path {
        DB("db"),
        REDIS("redis"),
        UNCHANGED("unchanged"),
        REBUILD("rebuild"),
        FALLBACK_DOWN("fallback_down"),
        FALLBACK_SEED("fallback_seed");

        private final String tag;

        Path(String tag) {
            this.tag = tag;
        }
    }

    /**
     * polling.readmodel 의 result 태그 중 서비스/어댑터가 기록하는 것
     */
    public enum Outcome {
        FALLBACK("fallback"),
        READ_REPAIR("read_repair"),
        PARSE_FAIL("parse_fail");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry meterRegistry;
    private final Map<Path, Timer> serviceTimer = new EnumMap<>(Path.class);
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private final Timer parseTimer;

    public PollingMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (Path path : Path.values()) {
            serviceTimer.put(path,
                    Timer.builder("polling.service")
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .tag("path", path.tag)
                            .register(meterRegistry)
            );
        }
        for (Outcome outcome : Outcome.values()) {
            counters.put(outcome,
                    Counter.builder("polling.readmodel")
                            .tag("result", outcome.tag)
                            .register(meterRegistry)
            );
        }
        this.parseTimer = Timer.builder("polling.readmodel.parse")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    public Timer.Sample start() {
        return Timer.start(meterRegistry);
    }

    public void stop(Timer.Sample sample, Path path) {
        sample.stop(serviceTimer.get(path));
    }

    public void count(Outcome outcome) {
        counters.get(outcome).increment();
    }

    public void stopParse(Timer.Sample sample) {
        sample.stop(parseTimer);
    }
}
