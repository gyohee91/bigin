package com.ghyinc.finance.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Redis 캐시 매니저 - productCode 전용
     */
    @Bean
    @Primary
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper cacheObjectMapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .modules(new JavaTimeModule())
                .build();
        cacheObjectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );

        RedisCacheConfiguration configuration = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofHours(1))  // TTL 1시간
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(cacheObjectMapper)    // JSON 직렬화
                        )
                );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .withCacheConfiguration("products",
                        configuration.entryTtl(Duration.ofHours(6))  // 상품 정보는 더 길게
                )
                .build();
    }

    /**
     * Caffeine 로컬 캐시 매니저 - CryptoFactory 전용
     */
    @Bean
    public CaffeineCacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("cryptoService");
        cacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(1, TimeUnit.HOURS)
        );
        return cacheManager;
    }
}
