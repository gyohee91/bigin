package com.ghyinc.finance.global.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Slf4j
public class RestTemplateLoggingInterceptor implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        //요청 로깅
        log.info(">>> URL: {}", request.getURI());
        log.info(">>> Method: {}", request.getMethod());
        log.info(">>> Headers: {}", request.getHeaders());
        log.info(">>> Request Body: {}", new String(body, StandardCharsets.UTF_8));

        long start = System.currentTimeMillis();

        ClientHttpResponse response = execution.execute(request, body);

        long duration = System.currentTimeMillis() - start;

        //응답 로깅
        log.info("<<< Status Code: {}", response.getStatusCode());
        log.info("<<< Status Text: {}", response.getStatusText());
        log.info("<<< Headers: {}", response.getHeaders());
        log.info("<<< Body: {}",
                new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining())
        );
        log.info("<<< Duration: {}ms", duration);

        return response;
    }
}
