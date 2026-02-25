package com.ghyinc.finance.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
public class RetryTemplateConfig {
    private static final int MAX_RETRY_ATTEMPTS = 3;

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        //Retry 정책: 어떤 예외를 몇 번 재시도할지
        retryTemplate.setRetryPolicy(this.retryPolicy());

        //BackOff 정책: 재시도 간격
        retryTemplate.setBackOffPolicy(this.backOffPolicy());

        //Retry 리스너 - 디버깅용
        retryTemplate.registerListener(this.retryListener());

        return retryTemplate;
    }

    private RetryPolicy retryPolicy() {
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();

        //재시도 대상 예외
        retryableExceptions.put(ResourceAccessException.class, true);   //네트워크 에러
        retryableExceptions.put(ConnectException.class, true);
        retryableExceptions.put(SocketTimeoutException.class, true);
        retryableExceptions.put(HttpServerErrorException.class, true);  //5xx 에러

        //최대 3번 재시도(총 4번 시도: 최초 1번 + 재시도 3번)
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_RETRY_ATTEMPTS, retryableExceptions);

        return retryPolicy;
    }

    private ExponentialBackOffPolicy backOffPolicy() {
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000); //첫 재시도: 1초 대기
        backOffPolicy.setMultiplier(2.0);       //다음 재시도: 2배씩 증가
        backOffPolicy.setMaxInterval(10000);    //최대 대기 기간: 10초

        //재시도 간격: 1초 -> 2초 -> 4초
        return backOffPolicy;
    }

    private RetryListener retryListener() {
        return new RetryListener() {
            @Override
            public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
                //Retry 시작 시 (최초 1회만)
                log.debug("Retry 시작 - Retry 이름: {}", context.getAttribute("context.name"));
                return true;
            }

            @Override
            public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                //Retry 종료 시 (성공 또는 실패)
                if(throwable != null) {
                    log.error("Retry 최종 실패 - 총 시도: {}, 최종 에러: {}",
                            context.getRetryCount() + 1,
                            throwable.getMessage()
                    );
                } else {
                    if(context.getRetryCount() > 0) {
                        log.info("Retry 성공 - 총 시도: {}", context.getRetryCount() + 1);
                    }
                }
            }

            @Override
            public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                //각 시도 실패 시마다 호출
                log.warn("Retry 에러 발생 - 시도: {}, 에러: {}",
                        context.getRetryCount() + 1,
                        throwable.getClass().getSimpleName()
                );
            }
        };
    }
}
