package com.ghyinc.finance.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP 요청 진입점에서 requestId를 MDC에 설정
 * <p>
 * [동작 흐름]
 * HTTP 요청 -> Filter -> MDC.put(requestId) -> Controller -> Service -> ... -> MDC.clear()
 * <p>
 * [설계 포인트]
 * - X-Request-Id 헤더가 있으면 그걸 사용 (Gateway나 클라이언트에서 그걸 전달할 경우)
 * - 없으면 UUID 새로 생성
 * - finally에서 반드시 MDC.clear() -> 스레드 풀 재사용시 이전 requestId 오염 방지
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_KEY = "requestId";
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String requestId = Optional.ofNullable(request.getHeader(REQUEST_ID_KEY))
                .orElse(UUID.randomUUID().toString());

        try {
            MDC.put(REQUEST_ID_KEY, requestId);
            // 응답 헤더에도 실어서 클라이언트가 추적 가능하게
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            // 스레드 풀 환경에서 반드시 clear
            MDC.clear();
        }
    }
}
