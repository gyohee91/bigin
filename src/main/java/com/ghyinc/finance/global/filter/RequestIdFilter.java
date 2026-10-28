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
import java.util.regex.Pattern;

import static com.ghyinc.finance.global.common.LoggingConstants.REQUEST_ID_KEY;

/**
 * HTTP 요청 진입점에서 requestId를 MDC에 설정
 * <p>
 * [동작 흐름]
 * HTTP 요청 -> Filter -> MDC.put(requestId) -> Controller -> Service -> ... -> MDC.clear()
 * <p>
 * [설계 포인트]
 * - X-Request-Id 헤더가 있고 형식([A-Za-z0-9._-], 최대 64자)이 맞으면 그걸 사용 (Gateway나 클라이언트에서 그걸 전달할 경우)
 * - 없으면 UUID 새로 생성
 * - finally에서 반드시 MDC.clear() -> 스레드 풀 재사용시 이전 requestId 오염 방지
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        // 요청 헤더(X-Request-Id)에서 읽는다. REQUEST_ID_KEY("requestId")는 MDC/Kafka 헤더용 키라 HTTP 헤더 이름이 아니다.
        // 클라이언트가 보낸 값은 로그에 그대로 찍히므로 형식·길이가 맞지 않으면 버리고 새로 발급한다.
        String requestId = Optional.ofNullable(request.getHeader(REQUEST_ID_HEADER))
                .filter(id -> VALID_REQUEST_ID.matcher(id).matches())
                .orElseGet(() -> UUID.randomUUID().toString());

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
