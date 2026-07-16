package com.ghyinc.finance.global.kafka.dlq.entity;

public enum DlqStatus {
    PENDING,    // 재시도 대기
    RETRYING,   // 재시도 중
    RESOLVED,   // 재처리 성공
    DEAD        // 최종 실패 (Poison Pill 또는 재시도 초과)
}
