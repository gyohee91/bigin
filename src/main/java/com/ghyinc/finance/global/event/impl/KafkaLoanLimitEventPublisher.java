package com.ghyinc.finance.global.event.impl;

import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.global.event.LoanLimitEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaLoanLimitEventPublisher implements LoanLimitEventPublisher {
    private final KafkaTemplate<String, LoanLimitCompletedEvent> kafkaTemplate;

    /**
     * ApplicationEventPublisher 대신 Kafka 발행
     * @param event
     */
    @Override
    public void publishCompletedEvent(LoanLimitCompletedEvent event) {
        // inquiryNo를 partition key로 사용
        // -> 동일 inquiry 이벤트는 항상 같은 파티션으로 전송
        kafkaTemplate.send("loan-limit-completed", event.getInquiryNo(), event)
                .whenComplete((result, ex) -> {
                    if(ex != null)
                        log.error("한도조회 완료 이벤트 발행 실패", ex);
                    else
                        log.info("한도조회 완료 이벤트 발생 성공. partition={}",
                                result.getRecordMetadata().partition());
                });
    }
}
