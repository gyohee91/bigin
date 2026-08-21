package com.ghyinc.finance.domain.audit.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String eventType;       // PARTNER_TRANSMISSION, PARTNER_CALLBACK

    @Column(nullable = false)
    private String aggregateId;     // inquiryNo 또는 loReqtNo

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;         // 수신 JSON 원문 그대로 저장 - 조회 시점에 재해석
}
