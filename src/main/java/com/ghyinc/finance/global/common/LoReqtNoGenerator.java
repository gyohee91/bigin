package com.ghyinc.finance.global.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 한도조회 상품별 신청번호 채번
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoReqtNoGenerator {
    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "SEQ";

    // INCR + 첫 채번 시 TTL 설정을 원자적으로 수행
    private static final RedisScript<Long> INCR_WITH_EXPIRE_SCRIPT = RedisScript.of(
                    """
                    local val = redis.call('INCR', KEYS[1])
                    if val == 1 then
                      redis.call('EXPIRE', KEYS[1], ARGV[1])
                    end
                    return val
                    """,
        Long.class
    );

    public String generate(String prefix) {
        String date = DateUtils.toDateString(LocalDateTime.now());

        // Redis Key: SEQ:LR:20260706
        String redisKey = KEY_PREFIX + ":" + prefix + ":" + date;
        long ttlSeconds = this.untilMidnight().toSeconds();

        Long seq = redisTemplate.execute(
                INCR_WITH_EXPIRE_SCRIPT,
                List.of(redisKey),
                String.valueOf(ttlSeconds)
        );

        if (seq > 99_999L) {
            log.error("[채번 한도 초과] prefix={}, date={}, seq={}", prefix, date, seq);
            throw new IllegalStateException(prefix + " 일일 채번 한도 초과. date=" + date + ", seq=" + seq);
        }

        return String.format("%s%s%05d", prefix, date, seq);
    }

    /**
     * 자정 기준 초기화 (익일 00:00 + 1시간)
     */
    private Duration untilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate()
                .plusDays(1)
                .atStartOfDay();

        return Duration.between(now, midnight).plusHours(1);
    }
}