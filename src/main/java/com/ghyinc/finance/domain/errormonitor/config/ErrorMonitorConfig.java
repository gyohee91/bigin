package com.ghyinc.finance.domain.errormonitor.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * @EnableRetry는 애플리케이션 전역 설정이다. 켜기 전에 프로젝트에서 "@Retryable"을 검색해서,
 * 지금까지 @EnableRetry가 없어 동작하지 않던 어노테이션이 남아있지 않은지 확인할 것.
 * (@EnableAsync 프록시가 바깥에 씌워지므로, 재시도는 alertExecutor 스레드 안에서 수행된다.)
 */
@Configuration
@EnableRetry
@EnableConfigurationProperties()
public class ErrorMonitorConfig {
}
