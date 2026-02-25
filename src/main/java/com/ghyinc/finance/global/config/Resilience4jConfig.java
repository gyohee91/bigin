package com.ghyinc.finance.global.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class Resilience4jConfig {
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @PostConstruct
    public void init() {
        log.info("========================================");
        log.info("Resilience4j 이벤트 리스너 초기화 시작");
        log.info("========================================");

        //Circuit Breaker 이벤트 리스너 등록
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(circuitBreaker -> {
            log.info("Circuit Breaker 등록: {}", circuitBreaker.getName());
            this.registerCircuitBreakerEventConsumer(circuitBreaker);
        });

        //동적으로 추가되는 Circuit Breaker도 자동 등록
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> {
                    log.info("Circuit Breaker 동적 추가: {}", event.getAddedEntry().getName());
                    this.registerCircuitBreakerEventConsumer(event.getAddedEntry());
                });

        //Retry 이벤트 리스너 등록
        retryRegistry.getAllRetries().forEach(retry -> {
            log.info("Retry 등록: {}", retry.getName());
            this.registerRetryEventConsumer(retry);
        });

        //동적 추가되는 Retry도 자동 등록
        retryRegistry.getEventPublisher()
                .onEntryAdded(event -> {
                    log.info("Retry 동적 추가: {}", event.getAddedEntry().getName());
                    this.registerRetryEventConsumer(event.getAddedEntry());
                });

        log.info("========================================");
        log.info("Resilience4j 이벤트 리스너 초기화 완료");
        log.info("========================================");
    }

    private void registerCircuitBreakerEventConsumer(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> {
                    CircuitBreaker.StateTransition transition = event.getStateTransition();
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                    log.warn("╔════════════════════════════════════════════════════════════");
                    log.warn("║ Circuit Breaker 상태 변경");
                    log.warn("║ 시간: {}", timestamp);
                    log.warn("║ 이름: {}", circuitBreaker.getName());
                    log.warn("║ 전환 {} -> {}",
                            transition.getFromState(),
                            transition.getToState()
                    );

                    //상태별 추가 정보
                    switch (transition.getToState()) {
                        case OPEN:
                            log.warn("║ 서비스 차단 - Fallback 실행");
                            log.warn("║ 대기시간: 30초 후 HALF_OPEN 전환");
                            break;
                        case HALF_OPEN:
                            log.warn("║ 테스트 모드 - 제한된 요청 허용");
                            log.warn("║ 테스트 횟수: 3번");
                            break;
                        case CLOSED:
                            if(transition.getFromState() == CircuitBreaker.State.HALF_OPEN) {
                                log.warn("║ 서비스 복구 완료");
                            }
                            break;
                    }

                    log.warn("╚════════════════════════════════════════════════════════════");
                })
                .onError(event -> {
                    CircuitBreaker.State state = circuitBreaker.getState();
                    CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

                    log.error("Circuit Breaker 에러 - 이름: {}, 상태:{}, 실패율: {}, 에러: {}",
                            circuitBreaker.getName(),
                            state,
                            metrics.getFailureRate(),
                            event.getThrowable().getMessage()
                    );
                })//성공 이벤트
                .onSuccess(event -> {
                    CircuitBreaker.State state = circuitBreaker.getState();

                    //HALF_OPEN 상태에서의 성공은 중요
                    if(state == CircuitBreaker.State.HALF_OPEN) {
                        CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
                        log.debug("HALF_OPEN 상태 성공 - 이름:{}, 성공 횟수:{}/3",
                                circuitBreaker.getName(),
                                metrics.getNumberOfSuccessfulCalls()
                        );
                    }
                })
                //호출 불가 이벤트(OPEN 상태)
                .onCallNotPermitted(event -> {
                    log.warn("호출 차단됨 (Circuit OPEN) - 이름: {}",
                            circuitBreaker.getName()
                    );
                })
                //Slow Call 이벤트
                .onSlowCallRateExceeded(event -> {
                    log.warn("느린 호출 임계치 초과 - 이름:{}, 느린 호출 비율: {}%",
                            circuitBreaker.getName(),
                            event.getSlowCallRate()
                    );
                })
                //Failure Rate 초과 이벤트
                .onFailureRateExceeded(event -> {
                    log.error("실패율 임계치 초과 - 이름: {}, 실패율: {}%",
                            circuitBreaker.getName(),
                            event.getFailureRate()
                    );
                });
    }

    /*
    @Bean
    public RegistryEventConsumer<CircuitBreaker> circuitBreakerEventConsumer() {
        return new RegistryEventConsumer<CircuitBreaker>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> entryAddedEvent) {
                CircuitBreaker circuitBreaker = entryAddedEvent.getAddedEntry();

                //상태 전환 이벤트
                circuitBreaker.getEventPublisher()
                        .onStateTransition(event -> {
                            CircuitBreaker.StateTransition transition = event.getStateTransition();
                            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                            log.warn("╔════════════════════════════════════════════════════════════");
                            log.warn("║ Circuit Breaker 상태 변경");
                            log.warn("║ 시간: {}", timestamp);
                            log.warn("║ 이름: {}", circuitBreaker.getName());
                            log.warn("║ 전환 {} -> {}",
                                    transition.getFromState(),
                                    transition.getToState()
                            );

                            //상태별 추가 정보
                            switch (transition.getToState()) {
                                case OPEN:
                                    log.warn("║ 서비스 차단 - Fallback 실행");
                                    log.warn("║ 대기시간: 30초 후 HALF_OPEN 전환");
                                    break;
                                case HALF_OPEN:
                                    log.warn("║ 테스트 모드 - 제한된 요청 허용");
                                    log.warn("║ 테스트 횟수: 3번");
                                    break;
                                case CLOSED:
                                    if(transition.getFromState() == CircuitBreaker.State.HALF_OPEN) {
                                        log.warn("║ 서비스 복구 완료");
                                    }
                                    break;
                            }

                            log.warn("╚════════════════════════════════════════════════════════════");
                        })
                        //에러 이벤트
                        .onError(event -> {
                            CircuitBreaker.State state = circuitBreaker.getState();
                            CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();

                            log.error("Circuit Breaker 에러 - 이름: {}, 상태:{}, 실패율: {}, 에러: {}",
                                    circuitBreaker.getName(),
                                    state,
                                    metrics.getFailureRate(),
                                    event.getThrowable().getMessage()
                            );
                        })
                        //성공 이벤트
                        .onSuccess(event -> {
                            CircuitBreaker.State state = circuitBreaker.getState();

                            //HALF_OPEN 상태에서의 성공은 중요
                            if(state == CircuitBreaker.State.HALF_OPEN) {
                                CircuitBreaker.Metrics metrics = circuitBreaker.getMetrics();
                                log.debug("HALF_OPEN 상태 성공 - 이름:{}, 성공 횟수:{}/3",
                                        circuitBreaker.getName(),
                                        metrics.getNumberOfSuccessfulCalls()
                                );
                            }
                        })
                        //호출 불가 이벤트(OPEN 상태)
                        .onCallNotPermitted(event -> {
                            log.warn("호출 차단됨 (Circuit OPEN) - 이름: {}",
                                    circuitBreaker.getName()
                            );
                        })
                        //Slow Call 이벤트
                        .onSlowCallRateExceeded(event -> {
                            log.warn("느린 호출 임계치 초과 - 이름:{}, 느린 호출 비율: {}%",
                                    circuitBreaker.getName(),
                                    event.getSlowCallRate()
                            );
                        })
                        //Failure Rate 초과 이벤트
                        .onFailureRateExceeded(event -> {
                            log.error("실패율 임계치 초과 - 이름: {}, 실패율: {}%",
                                    circuitBreaker.getName(),
                                    event.getFailureRate()
                            );
                        });
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> entryRemoveEvent) {
                log.info("Circuit Breaker 제거: {}", entryRemoveEvent.getRemovedEntry().getName());
            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> entryReplacedEvent) {
                log.info("Circuit Breaker 교체: {}", entryReplacedEvent.getNewEntry().getName());
            }
        };
    }

    @Bean
    public RetryEventConsumerRegistration retryEventConsumerRegistration(RetryRegistry registry) {
        registry.getEventPublisher()
                        .onEntryAdded(event -> this.registerRetryEventConsumer(event.getAddedEntry()));

        // 이미 등록된 Retry에도 이벤트 리스너 등록
        registry.getAllRetries()
                .forEach(this::registerRetryEventConsumer);

        return new RetryEventConsumerRegistration();
    }

     */

    private void registerRetryEventConsumer(Retry retry) {
        retry.getEventPublisher()
                .onRetry(event -> {
                    log.warn("╔════════════════════════════════════════════════════════════");
                    log.warn("║ Retry 시도");
                    log.warn("║ 이름: {}", retry.getName());
                    log.warn("║ {}번째 재시도 (총 {}번 재시도)",
                            event.getNumberOfRetryAttempts(),
                            event.getNumberOfRetryAttempts() + 1
                    );
                    log.warn("║ 대기 시간: {}ms", event.getWaitInterval().toMillis());
                    log.warn("║ 에러: {}", event.getLastThrowable().getClass().getSimpleName());
                    log.warn("╚════════════════════════════════════════════════════════════");
                })
                .onError(event -> {
                    log.error("╔════════════════════════════════════════════════════════════");
                    log.error("║ Retry 최종 실패");
                    log.error("║ 이름: {}", retry.getName());
                    log.error("║ 총 {}번 시도", event.getNumberOfRetryAttempts() + 1);
                    log.error("║ 최종 에러: {}", event.getLastThrowable().getMessage());
                    log.error("╚════════════════════════════════════════════════════════════");
                })
                .onSuccess(event -> {
                    if (event.getNumberOfRetryAttempts() > 0) {
                        log.info("╔════════════════════════════════════════════════════════════");
                        log.info("║ Retry 성공");
                        log.info("║ 이름: {}", retry.getName());
                        log.info("║ {}번째 시도에서 성공", event.getNumberOfRetryAttempts() + 1);
                        log.info("╚════════════════════════════════════════════════════════════");
                    }
                });
    }

    /*
    @Bean
    public RegistryEventConsumer<Retry> retryEventConsumer() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<Retry> entryAddedEvent) {
                Retry retry = entryAddedEvent.getAddedEntry();

                retry.getEventPublisher()
                        .onRetry(event -> {
                            log.warn("╔════════════════════════════════════════════════════════════");
                            log.warn("║ Retry 시도");
                            log.warn("║ 이름: {}", retry.getName());
                            log.warn("║ {}번째 재시도 (총 {}번 재시도)",
                                    event.getNumberOfRetryAttempts(),
                                    event.getNumberOfRetryAttempts() + 1
                            );
                            log.warn("║ 대기 시간: {}ms", event.getWaitInterval().toMillis());
                            log.warn("║ 에러: {}", event.getLastThrowable().getClass().getSimpleName());
                            log.warn("╚════════════════════════════════════════════════════════════");
                        })
                        .onError(event -> {
                            log.error("╔════════════════════════════════════════════════════════════");
                            log.error("║ Retry 최종 실패");
                            log.error("║ 이름: {}", retry.getName());
                            log.error("║ 총 {}번 시도", event.getNumberOfRetryAttempts() + 1);
                            log.error("║ 최종 에러: {}", event.getLastThrowable().getMessage());
                            log.error("╚════════════════════════════════════════════════════════════");
                        })
                        .onSuccess(event -> {
                            if (event.getNumberOfRetryAttempts() > 0) {
                                log.info("╔════════════════════════════════════════════════════════════");
                                log.info("║ Retry 성공");
                                log.info("║ 이름: {}", retry.getName());
                                log.info("║ {}번째 시도에서 성공", event.getNumberOfRetryAttempts() + 1);
                                log.info("╚════════════════════════════════════════════════════════════");
                            }
                        });
            }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<Retry> entryRemoveEvent) {

            }

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<Retry> entryReplacedEvent) {

            }
        };
    }

     */

    // 마커 클래스 (Bean 등록용)
    public static class RetryEventConsumerRegistration {

    }
}
