package com.ghyinc.finance.global.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ghyinc.finance.global.exception.KafkaMessageDeserializationException;
import com.ghyinc.finance.global.kafka.backoff.JitteredExponentialBackOff;
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
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.ghyinc.finance.global.common.LoggingConstants.REQUEST_ID_KEY;

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
        //FixedBackOff backOff = new FixedBackOff(1000L, 3L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer,
                new JitteredExponentialBackOff(1000L, 2.0, 4000L, 3, 0.3)   // ±30% jitter
        );

        // 파싱 오류는 재시도 없이 즉시 DLQ (Poison Pill 방지)
        // 재시도해도 계속 실패하는 예외들
        errorHandler.addNotRetryableExceptions(
                KafkaMessageDeserializationException.class,
                JsonProcessingException.class,
                InvalidRequestException.class,
                IllegalArgumentException.class  // Notification 없음 등
        );

        return errorHandler;
    }

    @Bean
    public DefaultErrorHandler dlqErrorHandler() {
        // DLT 컨슈머 자체가 실패해도 더 이상 재발행하지 않음 - 로그만 남기고 offset 커밋
        ConsumerRecordRecoverer terminalRecoverer = (record, ex) ->
                log.error("[DLQ-Terminal] DLT 메시지 최종 처리 실패. 재발행 안 함. topic={}, offset={}",
                        record.topic(), record.offset(), ex);
        return new DefaultErrorHandler(terminalRecoverer, new FixedBackOff(1000L, 2L));
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

    /**
     * 일반 비즈니스 Consumer용 - 실패 시 원본 토픽.DLT로 라우팅
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler errorHandler,
            RecordInterceptor<String, String> mdcRecordInterceptor
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setRecordInterceptor(mdcRecordInterceptor);
        return factory;
    }

    /**
     * DLT 토픽 전용 - DlqEventConsumer가 사용.
     * 여기서 또 실패해도 재발행하지 않는다 (재발행하면 *.DLT.DLT로 무한히 밀려남).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dlqKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            DefaultErrorHandler dlqErrorHandler,
            RecordInterceptor<String, String> mdcRecordInterceptor
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(dlqErrorHandler);
        factory.setRecordInterceptor(mdcRecordInterceptor);
        return factory;
    }

    /**
     * @RetryableTopic 전용 - 재시도/DLT 라우팅은 @RetryableTopic이 자체 처리하므로
     * commonErrorHandler를 따로 설정하지 않는다 (커스텀 errorHandler와 충돌 위험).
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> retryableTopicListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            RecordInterceptor<String, String> mdcRecordInterceptor
    ) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setRecordInterceptor(mdcRecordInterceptor);
        return factory;
    }
}
