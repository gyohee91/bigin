package com.ghyinc.finance.global.interceptor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Slf4j
public class LoggingRequestInterceptor implements ClientHttpRequestInterceptor {
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

        ClientHttpResponse bufferedResponse = new BufferingClientHttpResponseWrapper(response);

        //응답 로깅
        log.info("<<< Status Code: {}", bufferedResponse.getStatusCode());
        log.info("<<< Status Text: {}", bufferedResponse.getStatusText());
        log.info("<<< Headers: {}", bufferedResponse.getHeaders());
        log.info("<<< Body: {}",
                new BufferedReader(new InputStreamReader(bufferedResponse.getBody(), StandardCharsets.UTF_8))
                        .lines()
                        .collect(Collectors.joining())
        );
        log.info("<<< Duration: {}ms", duration);

        return bufferedResponse;
    }

    /**
     * Body 재읽기 가능하도록 버퍼링
     */
    private static class BufferingClientHttpResponseWrapper implements ClientHttpResponse {
        private final ClientHttpResponse response;
        private byte[] body;

        public BufferingClientHttpResponseWrapper(ClientHttpResponse response) throws IOException {
            this.response = response;
            this.body = response.getBody().readAllBytes();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return response.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return response.getStatusText();
        }

        @Override
        public void close() {
            response.close();
        }

        @Override
        public InputStream getBody() throws IOException {
            return new ByteArrayInputStream(body);      // 매번 새 스트림 반환
        }

        @Override
        public HttpHeaders getHeaders() {
            return response.getHeaders();
        }
    }
}
