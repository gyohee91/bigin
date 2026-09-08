package com.ghyinc.finance.domain.kcbcredit.dto;

import com.ghyinc.finance.domain.kcbcredit.enums.KcbCreditType;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * KCB 잔액/신용변동 파일 레코드 매핑 DTO
 * 실제 필드 순서/자릿수는 KCB 연동 스펙 문서에 맞춰 조정 필요
 */
@Getter
@Setter
public class KcbCreditRecord {
    private String ci;
    private String kcbCreditTypeCode;
    private BigDecimal beforeBalance;
    private BigDecimal afterBalance;
    private LocalDate changedAt;

    public KcbCreditType resolveKcbCreditType() {
        return switch (kcbCreditTypeCode) {
            case "01" -> KcbCreditType.BALANCE_INCREASE;
            case "02" -> KcbCreditType.BALANCE_DECREASE;
            case "09" -> KcbCreditType.CREDIT_SCORE_CHANGE;
            default -> KcbCreditType.NONE;
        };
    }
}
