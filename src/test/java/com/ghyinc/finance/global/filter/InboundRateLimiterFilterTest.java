package com.ghyinc.finance.global.filter;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.redisson.client.RedisTimeoutException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 인바운드 Rate Limiter 필터 단위 테스트. 실제 Redis 없이 Bucket4j ProxyManager 를 목으로 대체한다.
 *
 * <p>Redis 장애 시 로컬 폴백, 서킷 브레이커 OPEN 시 Redis 호출 생략을 검증한다.
 * 서킷 브레이커가 "Redis 호출(tryConsume)"을 감싸고 있어야 통과하는 테스트가 포함되어 있다.</p>
 */
class InboundRateLimiterFilterTest {

    private static final String TARGET_URI = "/api/loan/request-compare-loan";
    private static final String CB_NAME = "inboundRateLimiterRedis";
    private static final int LOCAL_CAPACITY = 20;   // 필터의 초당 한도와 동일
    private static final int BULKHEAD_MAX = 2;      // 테스트용 동시 Redis 호출 상한

    private RedissonBasedProxyManager<String> proxyManager;
    private RemoteBucketBuilder<String> bucketBuilder;
    private BucketProxy bucket;   // 모든 키에 대한 기본 버킷 (Redis 쪽 버킷 역할)
    private SimpleMeterRegistry meterRegistry;
    private CircuitBreakerRegistry circuitBreakerRegistry;
    private BulkheadRegistry bulkheadRegistry;
    private InboundRateLimiterFilter filter;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        // 딥스텁 대신 단계별로 명시적인 목을 쓴다. (do...when 스터빙 중 다른 목 호출이 끼어들면 Mockito 가 깨진다)
        proxyManager = mock(RedissonBasedProxyManager.class);
        bucketBuilder = mock(RemoteBucketBuilder.class);
        bucket = mock(BucketProxy.class);
        given(proxyManager.builder()).willReturn(bucketBuilder);
        given(bucketBuilder.build(anyString(), anyConfig())).willReturn(bucket);
        meterRegistry = new SimpleMeterRegistry();
        // 운영 설정(application.yaml)과 같은 의도: Redis 호출에서 발생한 모든 RuntimeException 을 실패로 집계
        circuitBreakerRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(RuntimeException.class)
                .build());
        // 꽉 차면 기다리지 않고 즉시 거절 (운영: max-wait-duration 0)
        bulkheadRegistry = BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(BULKHEAD_MAX)
                .maxWaitDuration(Duration.ZERO)
                .build());
        filter = new InboundRateLimiterFilter(proxyManager, circuitBreakerRegistry, bulkheadRegistry, meterRegistry);
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private static Supplier<BucketConfiguration> anyConfig() {
        return ArgumentMatchers.any();
    }

    /** 모든 키에 대해 Redis 가 tryConsume 결과로 allowed 를 돌려주게 한다. */
    private void redisReturns(boolean allowed) {
        // given(...tryConsume(1)) 형태는 이미 예외를 던지도록 스터빙된 메서드를 재스터빙할 때
        // 스터빙 도중 그 예외가 실제로 발생한다. 장애→회복 시나리오를 위해 do...when 형태를 쓴다.
        willReturn(allowed).given(bucket).tryConsume(1);
    }

    /** 모든 키에 대해 Redis 호출(tryConsume) 단계에서 장애가 발생하게 한다. */
    private void redisFails() {
        willThrow(new RedisTimeoutException("redis down")).given(bucket).tryConsume(1);
    }

    private MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", TARGET_URI);
        request.setRemoteAddr(ip);
        return request;
    }

    /** 필터를 한 번 통과시키고 응답 상태와 체인 통과 여부를 돌려준다. */
    private Result call(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Result(response, chain.getRequest() != null);
    }

    private record Result(MockHttpServletResponse response, boolean passed) {
        int status() {
            return response.getStatus();
        }
    }

    private double fallbackCount(String reason) {
        return meterRegistry.counter("inbound.ratelimiter.fallback", "reason", reason).count();
    }

    private Bulkhead bulkhead() {
        return bulkheadRegistry.bulkhead(CB_NAME);
    }

    /** 다른 요청이 Redis 응답을 기다리는 상황을 흉내 내 Bulkhead 허용량을 모두 점유한다. */
    private void occupyBulkhead() {
        for (int i = 0; i < BULKHEAD_MAX; i++) {
            assertThat(bulkhead().tryAcquirePermission()).isTrue();
        }
    }

    private CircuitBreaker circuitBreaker() {
        return circuitBreakerRegistry.circuitBreaker(CB_NAME);
    }

    /** 실패 호출을 윈도우 크기만큼 보내 서킷을 OPEN 으로 만든다. */
    private void openCircuit() throws Exception {
        redisFails();
        for (int i = 0; i < 4; i++) {
            call(request("10.9.9." + i));
        }
        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // ─── 대상 URI ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("대상이 아닌 URI 는 Redis 를 호출하지 않고 그대로 통과")
    void nonTargetUri_passesWithoutTouchingRedis() throws Exception {
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/loan/inquiry/LL123");
        other.setRemoteAddr("10.0.0.1");

        Result result = call(other);

        assertThat(result.passed()).isTrue();
        verify(proxyManager, never()).builder();
    }

    // ─── 정상 동작 (Redis 정상) ───────────────────────────────────────────────

    @Test
    @DisplayName("Redis 가 토큰을 내주면 요청이 통과하고 폴백 지표는 증가하지 않는다")
    void redisAllows_passes() throws Exception {
        redisReturns(true);

        Result result = call(request("10.0.0.1"));

        assertThat(result.passed()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(fallbackCount("redis_error")).isZero();
        assertThat(fallbackCount("circuit_open")).isZero();
    }

    @Test
    @DisplayName("Redis 토큰이 소진되면 429 + Retry-After 로 거절하고 체인으로 넘기지 않는다")
    void redisRejects_returns429() throws Exception {
        redisReturns(false);

        Result result = call(request("10.0.0.1"));

        assertThat(result.passed()).isFalse();
        assertThat(result.status()).isEqualTo(429);
        assertThat(result.response().getHeader("Retry-After")).isEqualTo("1");
        assertThat(result.response().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Too Many Requests");
    }

    @Test
    @DisplayName("토큰 소진(429)은 Redis 장애가 아니므로 서킷 실패로 집계되지 않고 폴백도 쓰지 않는다")
    void redisRejects_isNotCountedAsFailure() throws Exception {
        redisReturns(false);

        for (int i = 0; i < 10; i++) {
            call(request("10.0.0.1"));
        }

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(fallbackCount("redis_error")).isZero();
    }

    // ─── 클라이언트 키 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("X-Forwarded-For 가 있으면 첫 번째 IP 를 버킷 키로 사용")
    void clientKey_usesFirstForwardedAddress() throws Exception {
        // 해당 키로만 허용, 그 외 키는 목의 기본값(false → 429)
        BucketProxy allowedBucket = mock(BucketProxy.class);
        given(allowedBucket.tryConsume(1)).willReturn(true);
        given(bucketBuilder.build(eq("203.0.113.5"), anyConfig())).willReturn(allowedBucket);
        MockHttpServletRequest request = request("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");

        Result result = call(request);

        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("X-Forwarded-For 가 없으면 remoteAddr 을 버킷 키로 사용")
    void clientKey_fallsBackToRemoteAddr() throws Exception {
        BucketProxy allowedBucket = mock(BucketProxy.class);
        given(allowedBucket.tryConsume(1)).willReturn(true);
        given(bucketBuilder.build(eq("10.0.0.7"), anyConfig())).willReturn(allowedBucket);

        Result result = call(request("10.0.0.7"));

        assertThat(result.passed()).isTrue();
    }

    // ─── Redis 장애: 로컬 폴백 ────────────────────────────────────────────────

    @Test
    @DisplayName("Redis 호출이 예외로 실패해도 5xx 가 아니라 로컬 폴백으로 요청을 처리")
    void redisFailure_fallsBackToLocalBucket() throws Exception {
        redisFails();

        Result result = call(request("10.0.0.1"));

        assertThat(result.passed()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(fallbackCount("redis_error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Redis 장애 중에도 로컬 한도(초당 20건)를 넘으면 429 로 거절 - 방어선 유지")
    void redisFailure_localLimitStillEnforced() throws Exception {
        redisFails();

        int passed = 0;
        int rejected = 0;
        // 한도보다 충분히 많이 보낸다. (토큰이 시간에 따라 조금 채워질 수 있어 정확히 20+1 이 아니라 범위로 검증)
        for (int i = 0; i < 40; i++) {
            Result result = call(request("10.0.0.1"));
            if (result.passed()) {
                passed++;
            } else {
                assertThat(result.status()).isEqualTo(429);
                rejected++;
            }
        }

        assertThat(passed).isBetween(LOCAL_CAPACITY, LOCAL_CAPACITY + 3);
        assertThat(rejected).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("로컬 폴백 버킷은 클라이언트(IP)별로 독립")
    void redisFailure_localBucketsAreIndependentPerClient() throws Exception {
        redisFails();
        for (int i = 0; i < 40; i++) {
            call(request("10.0.0.1"));   // 10.0.0.1 의 로컬 버킷을 소진
        }

        Result other = call(request("10.0.0.2"));

        assertThat(other.passed()).isTrue();
    }

    // ─── 서킷 브레이커 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Redis 실패가 누적되면 서킷이 OPEN - tryConsume 단계의 장애도 서킷에 집계되어야 한다")
    void redisFailures_openTheCircuit() throws Exception {
        redisFails();

        for (int i = 0; i < 4; i++) {
            call(request("10.9.9." + i));
        }

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isEqualTo(4);
    }

    @Test
    @DisplayName("서킷 OPEN 이후에는 Redis 를 호출하지 않고 즉시 로컬 폴백으로 처리 (타임아웃 대기 없음)")
    void circuitOpen_skipsRedisAndUsesLocalFallback() throws Exception {
        openCircuit();
        clearInvocations(proxyManager);

        Result result = call(request("10.0.0.1"));

        assertThat(result.passed()).isTrue();
        verify(proxyManager, never()).builder();
        assertThat(fallbackCount("circuit_open")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("서킷 OPEN 중에도 로컬 한도는 적용된다")
    void circuitOpen_localLimitStillEnforced() throws Exception {
        openCircuit();

        int rejected = 0;
        for (int i = 0; i < 40; i++) {
            if (!call(request("10.0.0.1")).passed()) {
                rejected++;
            }
        }

        assertThat(rejected).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Redis 회복 후 HALF_OPEN 에서 성공하면 서킷이 닫히고 다시 Redis 를 사용")
    void redisRecovery_closesCircuit() throws Exception {
        openCircuit();
        circuitBreaker().transitionToHalfOpenState();
        redisReturns(true);

        call(request("10.0.0.1"));
        call(request("10.0.0.2"));

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(bucketBuilder, org.mockito.Mockito.atLeastOnce()).build(anyString(), anyConfig());
    }

    // ─── Bulkhead (Redis 를 기다리는 스레드 수 상한) ──────────────────────────

    @Test
    @DisplayName("Bulkhead 가 가득 차면 Redis 를 호출하지 않고 즉시 로컬 폴백으로 처리")
    void bulkheadFull_skipsRedisAndUsesLocalFallback() throws Exception {
        occupyBulkhead();
        clearInvocations(proxyManager);

        Result result = call(request("10.0.0.1"));

        assertThat(result.passed()).isTrue();
        verify(proxyManager, never()).builder();
        assertThat(fallbackCount("bulkhead_full")).isEqualTo(1.0);
        assertThat(fallbackCount("redis_error")).isZero();
        assertThat(fallbackCount("circuit_open")).isZero();
    }

    @Test
    @DisplayName("Bulkhead 포화는 Redis 장애가 아니므로 서킷 실패로 집계되지 않는다 (서킷 바깥에서 거절)")
    void bulkheadFull_isNotCountedAsCircuitFailure() throws Exception {
        occupyBulkhead();

        for (int i = 0; i < 10; i++) {
            call(request("10.0.0." + i));
        }

        assertThat(circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(circuitBreaker().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    @DisplayName("Bulkhead 포화 중에도 로컬 한도는 적용된다")
    void bulkheadFull_localLimitStillEnforced() throws Exception {
        occupyBulkhead();

        int rejected = 0;
        for (int i = 0; i < 40; i++) {
            if (!call(request("10.0.0.1")).passed()) {
                rejected++;
            }
        }

        assertThat(rejected).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("정상 호출 후 Bulkhead 허용량이 반환된다 (누수 없음)")
    void bulkhead_permitReleasedAfterSuccess() throws Exception {
        redisReturns(true);

        for (int i = 0; i < 10; i++) {
            call(request("10.0.0.1"));
        }

        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(BULKHEAD_MAX);
    }

    @Test
    @DisplayName("Redis 예외로 실패한 호출도 Bulkhead 허용량을 반환한다 (누수 없음)")
    void bulkhead_permitReleasedAfterFailure() throws Exception {
        redisFails();

        for (int i = 0; i < 3; i++) {   // 서킷이 OPEN 되기 전까지의 호출
            call(request("10.0.0." + i));
        }

        assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(BULKHEAD_MAX);
    }

    @Test
    @DisplayName("Redis 응답을 기다리는 요청이 상한만큼 있어도, 추가 요청은 기다리지 않고 즉시 처리된다")
    void concurrentSlowRedis_extraRequestsAreNotBlocked() throws Exception {
        CountDownLatch entered = new CountDownLatch(BULKHEAD_MAX);
        CountDownLatch release = new CountDownLatch(1);
        // Redis 응답이 오지 않는 상황: 호출한 스레드는 release 될 때까지 블로킹
        given(bucket.tryConsume(1)).willAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return true;
        });

        ExecutorService pool = Executors.newFixedThreadPool(BULKHEAD_MAX);
        try {
            // Bulkhead 허용량만큼의 요청이 Redis 응답을 기다리며 스레드를 점유
            Future<Result> first = pool.submit(() -> call(request("10.1.0.1")));
            Future<Result> second = pool.submit(() -> call(request("10.1.0.2")));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            // 추가 요청은 Redis 를 기다리지 않고 바로 로컬 폴백으로 끝나야 한다
            long startNanos = System.nanoTime();
            Result extra = call(request("10.1.0.3"));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertThat(extra.passed()).isTrue();
            assertThat(elapsedMillis).isLessThan(1_000);   // release(최대 10초)를 기다렸다면 크게 초과
            assertThat(fallbackCount("bulkhead_full")).isEqualTo(1.0);
            assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isZero();

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).passed()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).passed()).isTrue();
            assertThat(bulkhead().getMetrics().getAvailableConcurrentCalls()).isEqualTo(BULKHEAD_MAX);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
