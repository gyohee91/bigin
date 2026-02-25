package com.ghyinc.finance.global.config;

import com.ghyinc.finance.global.interceptor.RestTemplateLoggingInterceptor;
import com.ghyinc.finance.global.interceptor.RestTemplateRetryInterceptor;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(
            RestTemplateBuilder builder,
            RetryTemplate retryTemplate
    ) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(10));

        return builder
                .requestFactory(this::bufferingClientHttpRequestFactory)
                .additionalInterceptors(new RestTemplateLoggingInterceptor())
                //RetryInterceptor 제거 - Resilience4j로 대체
                //.additionalInterceptors(new RestTemplateRetryInterceptor(retryTemplate))
                .build();
    }

    /**
     * 요청/응답 로깅을 위한 Buffering Factory
     */
    private ClientHttpRequestFactory bufferingClientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        return new BufferingClientHttpRequestFactory(factory);
    }
}
