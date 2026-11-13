package com.ghyinc.finance.global.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class InboundRateLimiterFilter extends OncePerRequestFilter {
    private final RedissonBasedProxyManager<String> bucket4jProxyManager;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;
    private final MeterRegistry meterRegistry;

    // 로컬 폴백용 버킷. IP 키가 무한히 늘지 않도록 만료를 둔다
    private final Cache<String, Bucket> localBuckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();

    private Bucket localBucket(String key) {
        return localBuckets.get(key, k -> Bucket.builder()
                .addLimit(limit ->
                        limit.capacity(20).refillGreedy(20, Duration.ofSeconds(1)))
                .build());
    }

    private static final Supplier<BucketConfiguration> CONFIG_SUPPLIER = () -> BucketConfiguration.builder()
            .addLimit(limit -> limit
                    .capacity(20)
                    .refillGreedy(20, Duration.ofSeconds(1)))   // 초당 20건, 클라이언트(IP)당 - 인스턴스 수 무관
            .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/loan/request-compare-loan")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = this.resolveClientKey(request);

        if (!tryConsume(clientKey)) {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                    "{\"error\":\"Too Many Requests\",\"message\":\"요청이 너무 많습니다. 잠시 후 다시 시도해주세요.\"}"
            );

            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private boolean tryConsume(String clientKey) {
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker("inboundRateLimiterRedis");
        Bulkhead bulkhead = bulkheadRegistry.bulkhead("inboundRateLimiterRedis");

        try {
            // 바깥 Bulkhead(동시 대기수 제한) → 안쪽 CircuitBreaker(실패 집계) → Redis 호출
            // Redis 호출만 감싼다. 토큰 부족(false)은 예외가 아니므로 실패로 집계되지 않는다
            return Bulkhead.decorateSupplier(bulkhead,
                    CircuitBreaker.decorateSupplier(circuitBreaker, () ->
                            bucket4jProxyManager.builder().build(clientKey, CONFIG_SUPPLIER).tryConsume(1))
            ).get();
        } catch (BulkheadFullException e) {
            // Bulkhead Full
            meterRegistry.counter("inbound.ratelimiter.fallback", "reason", "bulkhead_full").increment();
            return localBucket(clientKey).tryConsume(1);
        } catch (CallNotPermittedException e) {
            // Circuit Breaker OPEN: Redis를 부르지 않고 바로 폴백 (타임아웃 대기 없음)
            meterRegistry.counter("inbound.ratelimiter.fallback", "reason", "circuit_open").increment();
            return localBucket(clientKey).tryConsume(1);
        } catch (RuntimeException e) {
            log.warn("Rate Limiter Redis 장애, 로컬 폴백 사용: {}", e.toString());
            meterRegistry.counter("inbound.ratelimiter.fallback", "reason", "redis_error").increment();
            return localBucket(clientKey).tryConsume(1);
        }
    }
}
