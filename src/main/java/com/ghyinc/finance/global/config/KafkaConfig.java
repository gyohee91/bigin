package com.ghyinc.finance.global.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Spring Kafka 자동 DLQ
 */
@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> kafkaTemplate) {

        // DLQ 라우팅 - 실패한 메시지를 원본 토픽.DLT로 이동
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (ConsumerRecord<?, ?> record, Exception ex) -> {
                    log.error("[DLQ] 메시지 처리 최종 실패. topic={}, offset={}, cause={}",
                            record.topic(), record.offset(), ex.getMessage());
                    // notification.send → notification.send.DLT
                    return new TopicPartition(record.topic() + ".DLT", record.partition());
                }
        );

        // 재시도 3회, 1초 간격 후 DLQ로 이동
        FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // 파싱 오류는 재시도 없이 즉시 DLQ (Poison Pill 방지)
        // 재시도해도 계속 실패하는 예외들
        errorHandler.addNotRetryableExceptions(
                JsonProcessingException.class,
                InvalidRequestException.class,
                IllegalArgumentException.class  // Notification 없음 등
        );

        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);    // DLQ ErrorHandler 적용
        return factory;
    }
}
