package com.ghyinc.finance.global.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.ghyinc.finance.global.filter.RequestIdFilter.REQUEST_ID_KEY;

/**
 * Spring Kafka 자동 DLQ + Consumer 공통 MDC(requestId) 전파
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
    public RecordInterceptor<String, String> mdcRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                Header header = record.headers().lastHeader(REQUEST_ID_KEY);
                String requestId = header != null
                        ? new String(header.value(), StandardCharsets.UTF_8)
                        : UUID.randomUUID().toString();
                MDC.put(REQUEST_ID_KEY, requestId);
                return record;
            }

            /**
             * Kafka 레코드 헤더의 requestId를 MDC에 복원/정리한다
             * <p>
             * interceptor()는 리스너 호출 직전, success()/failure()는 호출 직후 (성공/실패 여부와 무관하게
             * 둘 중 하나는 반드시 호출됨) 실행되어 try/finally 없이도 MDC 오염 방지한다.
             * <p>
             * 이 factory를 사용하는 모든 @KafkaListener에 공통 적용된다 - 개별 Consumer는
             * MDC.put()/MDC.clear()를 직접 다룰 필요가 없다.
             */
            @Override
            public void success(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                MDC.clear();
            }

            @Override
            public void failure(ConsumerRecord<String, String> record, Exception exception, Consumer<String, String> consumer) {
                MDC.clear();
            }
        };
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            RecordInterceptor<String, String> mdcRecordInterceptor
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);    // DLQ ErrorHandler 적용
        factory.setRecordInterceptor(mdcRecordInterceptor);
        return factory;
    }
}
