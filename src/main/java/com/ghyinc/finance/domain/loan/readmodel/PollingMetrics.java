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
        // 읽기
        HIT("hit"),
        UNCHANGED("unchanged"),
        MISS("miss"),
        STALE("stale"),
        READ_FAIL("read_fail"),
        // 쓰기
        WRITE_OK("write_ok"),
        WRITE_FAIL("write_fail"),
        WRITE_SKIPPED_NO_META("write_skipped_no_meta"),
        WRITE_SKIPPED_DOWN("write_skipped_down"),           // Redis 구간이라 쓰기를 건너뜀(reconcile 로 복귀)
        WRITE_FAIL_SERIALIZE("write_fail_serialize"), // 직렬화/인자 조립 실패(Redis 문제는 아님 -> down 처리 안함)
        // 서비스/어댑터
        FALLBACK("fallback"),
        READ_REPAIR("read_repair"),
        PARSE_FAIL("parse_fail");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    /**
     * polling.readmodel.redis 의 op 태그
     */
    public enum RedisOp {
        READ("read"),
        WRITE("write");

        private final String tag;

        RedisOp(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry meterRegistry;
    private final Map<Path, Timer> serviceTimer = new EnumMap<>(Path.class);
    private final Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    private final Map<RedisOp, Timer> redisOkTimers = new EnumMap<>(RedisOp.class);
    private final Map<RedisOp, Timer> redisErrorTimers = new EnumMap<>(RedisOp.class);
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
        for (RedisOp op : RedisOp.values()) {
            redisOkTimers.put(op, this.redisTimer(op, "ok"));
            redisErrorTimers.put(op, this.redisTimer(op, "error"));
        }
        this.parseTimer = Timer.builder("polling.readmodel.parse")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    private Timer redisTimer(RedisOp op, String outcome) {
        return Timer.builder("polling.readmodel.redis")
                .description("Redis 읽기모델 호출 시간 (redis.execute 구간만)")
                .tag("op", op.tag)
                .tag("outcome", outcome)
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

    public void stopRedis(Timer.Sample sample, RedisOp op, boolean ok) {
        sample.stop((ok ? redisOkTimers : redisErrorTimers).get(op));
    }

    public void stopParse(Timer.Sample sample) {
        sample.stop(parseTimer);
    }
}
