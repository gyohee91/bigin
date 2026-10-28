package com.ghyinc.finance.global.metrics;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 파트너사별 전송 SLA 지표 기록기.
 * <p>
 * 전송 결과를 얻는 시점에 앱 안에서 바로 Micrometer 카운터/타이머를 기록한다.
 * 지표 계산은 메모리 연산이라 Kafka 컨슈머를 거칠 이유가 없고, 재전달에 의한 중복 집계도 없다.
 * (인스턴스별 지표는 Prometheus가 합산한다)
 * <p>
 * 지표 수집은 완전성보다 가용성 우선이므로 실패해도 비즈니스 흐름에 예외를 전파하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerSlaMetrics {
    private final MeterRegistry meterRegistry;

    public void recordTransmission(LoanLimitAdaptorResponse response) {
        try {
            String partner = response.partnerCode().name();
            String result = response.success() ? "success" : "fail";

            Counter.builder("partner.transmission.count")
                    .tag("partner", partner)
                    .tag("result", result)
                    .description("파트너별 전송 성공/실패 건수")
                    .register(meterRegistry)
                    .increment();

            Timer.builder("partner.transmission.duration")
                    .tag("partner", partner)
                    .tag("result", result)
                    .description("파트너별 응답 시간")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry)
                    .record(response.resTimeMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[SLA-Metrics] 지표 기록 실패", e);
        }
    }
}
