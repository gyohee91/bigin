package com.ghyinc.finance.global.metrics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.event.PartnerTransmissionAuditEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PartnerSlaMetricsConsumerTest {
    private SimpleMeterRegistry meterRegistry;
    private PartnerSlaMetricsConsumer consumer;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        consumer = new PartnerSlaMetricsConsumer(meterRegistry, objectMapper);
    }

    private ConsumerRecord<String, String> buildRecord(String payload) {
        return new ConsumerRecord<>("audit.partner-transmission", 0, 0L, "inquiry-1", payload);
    }

    @Test
    @DisplayName("성공 이벤트를 수신하면 partner/result 태그로 카운터와 타이머를 기록한다")
    void consume_recordsCounterAndTimer_onSuccessEvent() throws JsonProcessingException {
        PartnerTransmissionAuditEvent event = PartnerTransmissionAuditEvent.builder()
                .inquiryNo("INQ-1")
                .partnerCode(PartnerCode.TOSS_BANK)
                .success(true)
                .resTimeMs(120)
                .occurredAt(LocalDateTime.now())
                .build();
        String payload = objectMapper.writeValueAsString(event);

        consumer.consume(payload, this.buildRecord(payload));

        double count = meterRegistry.get("partner.transmission.count")
                .tag("partner", "TOSS_BANK")
                .tag("result", "success")
                .counter().count();
        assertThat(count).isEqualTo(1.0);

        double totalMs = meterRegistry.get("partner.transmission.duration")
                .tag("partner", "TOSS_BANK")
                .tag("result", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS);

        assertThat(totalMs).isEqualTo(120.0);
    }

    @Test
    @DisplayName("실패 이벤트는 result=fail 태그로 별도 집계된다")
    void consume_tagsAsFail_onFailureEvent() throws JsonProcessingException {
        PartnerTransmissionAuditEvent event = PartnerTransmissionAuditEvent.builder()
                .inquiryNo("INQ-2")
                .partnerCode(PartnerCode.TOSS_BANK)
                .success(false)
                .failReason("TIMEOUT")
                .resTimeMs(7000)
                .occurredAt(LocalDateTime.now())
                .build();
        String payload = objectMapper.writeValueAsString(event);

        consumer.consume(payload, this.buildRecord(payload));

        double count = meterRegistry.get("partner.transmission.count")
                .tag("partner", "TOSS_BANK")
                .tag("result", "fail")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("payload 파싱에 실패해도 예외를 전파하지 않는다")
    void consume_neverThrows_onMalformedPayload() {
        String badPayload = "not-a-json";

        assertThatCode(() -> consumer.consume(badPayload, this.buildRecord(badPayload)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 partner+result 조합이 반복되면 카운터가 누적된다")
    void consume_accumulatesCounter_acrossMultipleEvents() throws JsonProcessingException {
        PartnerTransmissionAuditEvent event = PartnerTransmissionAuditEvent.builder()
                .inquiryNo("INQ-3")
                .partnerCode(PartnerCode.TOSS_BANK)
                .success(true)
                .resTimeMs(100)
                .occurredAt(LocalDateTime.now())
                .build();
        String payload = objectMapper.writeValueAsString(event);

        consumer.consume(payload, this.buildRecord(payload));
        consumer.consume(payload, this.buildRecord(payload));

        double count = meterRegistry.get("partner.transmission.count")
                .tag("partner", "TOSS_BANK")
                .tag("result", "success")
                .counter().count();
        assertThat(count).isEqualTo(2.0);
    }
}