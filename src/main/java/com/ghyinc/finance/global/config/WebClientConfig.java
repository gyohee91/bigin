package com.ghyinc.finance.global.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient externalApiWebClient() {
        HttpClient httpClient = HttpClient.create()
                // TCP 연결 수립 타임아웃
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3_000)
                // 응답 전체 타임아웃 (요청 시작 후부터 측정)
                .responseTimeout(Duration.ofSeconds(8))
                .doOnConnected(connection ->
                        connection
                                //응답 데이터 수신 타임아웃
                                .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS))
                                //요청 데이터 전송 타임아웃
                                .addHandlerLast(new WriteTimeoutHandler(3, TimeUnit.SECONDS))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .filter(this.loggingFilter())
                .build();
    }

    private ExchangeFilterFunction loggingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            //MDC requestId는 .block() 호출 시 같은 스레드이므로 MDC 접근 가능
            String requestId = MDC.get("requestId");
            log.debug("[WebClient] >>> {} {} requestId: {}",
                    request.method(), request.url(), requestId);
            return Mono.just(request);
        });
    }

}
