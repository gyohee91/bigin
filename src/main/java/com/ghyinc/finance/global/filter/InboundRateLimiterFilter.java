package com.ghyinc.finance.global.filter;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
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

@Component
@RequiredArgsConstructor
public class InboundRateLimiterFilter extends OncePerRequestFilter {
    private final RateLimiterRegistry rateLimiterRegistry;

    private static final RateLimiterConfig CONFIG = RateLimiterConfig.custom()
            .limitForPeriod(20)                 // 클라이언트(IP)당 초당 20건
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)     // 대기 없이 즉시 거절
            .build();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith("/api/loan/request-compare-loan")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientKey = this.resolveClientKey(request);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("inbound:" + clientKey, CONFIG);

        if (!rateLimiter.acquirePermission()) {
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
