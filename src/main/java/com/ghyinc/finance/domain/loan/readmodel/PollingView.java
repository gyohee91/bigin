package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;

import java.util.List;

/**
 * 폴링용 읽기모델 조회 결과. Redis 저장 형식 (JSON 문자열, 내부 응답 코드)을 노출하지 않는다.
 * <ul>
 *     <li>{@link Hit}: 값이 있고 본문 포함</li>
 *     <li>{@link Unchanged}: 클라이언트 버전과 동일 (본문 생략)</li>
 *     <li>{@link Miss}: 없음 / 오래됨(Stale) / 저장본 파싱 불가 → DB에서 다시 만들어야 함</li>
 *     <li>{@link Down}: Redis 장애 → 기존 DB 경로로</li>
 * </ul>
 */
public sealed interface PollingView {
    /** 읽기 모델에 값이 있는 경우 (Hit / Unchanged) */
    sealed interface Found extends PollingView {
        long version();
        String status();
        long deadlineAtMs();
    }

    record Hit(long version, int total, int success, String status, long totalElements,
               List<LoanLimitProductResultDto> items, long deadlineAtMs) implements Found {}

    record Unchanged(long version, String status, long deadlineAtMs) implements Found {}

    enum Miss implements PollingView {
        INSTANCE
    }

    enum Down implements PollingView {
        INSTANCE
    }
}
