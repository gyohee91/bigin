package com.ghyinc.finance.domain.loan.enums;

/**
 * 한도조회 진행 상태.
 *
 * <pre>
 * ACCEPTED → PENDING → IN_PROGRESS → SUCCESS | PARTIAL_SUCCESS | FAILED
 *   (Redis 락 경로는 ACCEPTED 없이 PENDING 부터 시작, 어느 단계에서든 오류 시 FAILED)
 * </pre>
 *
 * FAILED 가 되면 {@code dedup_key} 가 해제되어 당일 재조회가 가능하다.
 */
public enum InquiryStatus {
    /** DB 선점 완료, 아직 외부 조회·검증 전. Redis 장애 시 2차 경로에서만 존재하는 중간 상태 */
    ACCEPTED,
    /** 접수 완료, 금융사 전송 시작 전 (이벤트 처리 대기) */
    PENDING,
    /** 모든 금융사 결과 정상 */
    SUCCESS,
    /** 선저장 완료, 금융사 팬아웃·콜백 수신 중 */
    IN_PROGRESS,
    /** 일부 금융사만 성공 */
    PARTIAL_SUCCESS,
    /** 실패 (종료 상태, 당일 재조회 허용) */
    FAILED
}
