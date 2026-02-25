package com.ghyinc.finance.global.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;

@Slf4j
@RequiredArgsConstructor
public class RestTemplateRetryInterceptor implements ClientHttpRequestInterceptor {
    private final RetryTemplate retryTemplate;

    private static final int MAX_RETRY_ATTEMPTS = 3;

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        return retryTemplate.execute(context -> {
            if(context.getRetryCount() > 0) {
                log.warn("재시도 중... {}번째 재시도 - URI: {}",
                        context.getRetryCount() + 1,
                        request.getURI()
                );
            }

            try {
                return execution.execute(request, body);
            } catch (Exception e) {
                log.error("API 호출 실패- URI: {}, 시도: {}/{}",
                        request.getURI(),
                        context.getRetryCount() + 1,
                        MAX_RETRY_ATTEMPTS,
                        e
                );

                throw e;
            }
        });
    }
}
