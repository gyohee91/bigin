package com.ghyinc.finance.global.filter;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.redis.redisson.cas.RedissonBasedProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class InboundRateLimiterFilter extends OncePerRequestFilter {
    private final RedissonBasedProxyManager<String> bucket4jProxyManager;

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
        Bucket bucket = bucket4jProxyManager.builder().build(clientKey, CONFIG_SUPPLIER);

        if (!bucket.tryConsume(1)) {
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
}
