package com.ghyinc.finance.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka 토픽 생성 설정
 *
 * [운영 포인트]
 * - 로컬/개발: @Bean으로 자동 생성 편의
 * - 운영: 토픽을 사전에 수동 생성하고 이 Config는 제거하나 프로파일로 분리
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic notificationSendTopic() {
        return TopicBuilder.name("notification.send")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
