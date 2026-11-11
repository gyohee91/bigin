package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;

import java.util.List;

/**
 * 금융사 전송 계획. {@code LoanLimitDispatchPlanner#plan} 의 결과로, 이후 Inquiry 저장과
 * 팬아웃 이벤트에 그대로 실려 간다.
 *
 * @param availablePartnerCodes 실제로 조회할 금융사 (지원 금융사 중 외부 데이터 실패로 제외된 곳을 뺀 목록)
 * @param adaptorRequest        금융사 전송용 공통 요청 DTO
 */
public record PartnerDispatchPlan(
        List<PartnerCode> availablePartnerCodes,
        LoanLimitAdaptorRequest adaptorRequest) {
}
