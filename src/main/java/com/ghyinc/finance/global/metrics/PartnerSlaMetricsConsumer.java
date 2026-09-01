package com.ghyinc.finance.global.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.global.event.PartnerTransmissionAuditEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 파트너사별 SLA 모니터링 전용 컨슈머.
 * <p>
 * audit-log-group과 별개의 독립된 컨슈머 그룹으로 같은 토픽(audit.partner-transmission)을 구독한다.
 * DB 조회 없이 이벤트 payload만으로 Micrometer 카운터/타이머를 찍어서 가볍고, audit-log-group이
 * 느려지거나 장애가 나도 이 컨슈머의 지표 수집에는 영향이 없다(반대도 마찬가지) - 별도 그룹이라 완전히 독립적
 * <p>
 * 지표 수집은 완전성보다 가용성 우선이라, 파싱/처리 실패 시 재시도·DLT로 보내지 않고
 * 로그만 남긴 뒤 다음 메시지로 넘어간다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PartnerSlaMetricsConsumer {
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "audit.partner-transmission",
            groupId = "partner-sla-metrics-group"
    )
    public void consume(String payload, ConsumerRecord<String, String> record) {
        try {
            PartnerTransmissionAuditEvent event = objectMapper.readValue(payload, PartnerTransmissionAuditEvent.class);

            String partner = event.partnerCode().name();
            String result = event.success() ? "success" : "fail";

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
                    .record(event.resTimeMs(), TimeUnit.MILLISECONDS);

        } catch (Exception e) {
            // 지표 수집 실패가 비즈니스 처리에 영향을 주면 안 되므로 절대 예외를 전파하지 않는다.
            log.warn("[SLA-Metrics] 이벤트 처리 실패. 해당 건 지표만 유실. partition={}, offset={}",
                    record.partition(), record.offset());
        }
    }
}
