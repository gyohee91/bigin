package com.ghyinc.finance.global.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerConnectionEvictionScheduler {
    private final PoolingHttpClientConnectionManager partnerConnectionManager;

    // 파트너별 keepAliveSec은 closeExpired()가 처리하므로 이 값은 만료 누락을 막는 안전망
    private static final long MAX_IDLE_SEC = 60;

    // 인스턴스마다 자기 풀을 관리해야 하므로 ShedLock을 쓰지 않는다.
    @Scheduled(fixedDelay = 10_000)
    public void evict() {
        try {
            partnerConnectionManager.closeExpired();
            partnerConnectionManager.closeIdle(TimeValue.ofSeconds(MAX_IDLE_SEC));
        } catch (Exception e) {
            log.warn("[HTTP 커넥션 풀] 만료/유휴 커넥션 정리 실패", e);
        }
    }
}
