package com.ghyinc.finance.domain.loan.dto;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;

import java.util.List;
import java.util.Map;

/**
 * {@code LoanLimitInquiryPersistenceService#preSave()}의 결과물.
 *
 * <p>선저장 트랜잭션(짧게, commit)에서 만든 Result/ProductResult는 커밋과 동시에
 * Hikari 커넥션을 반납해야 하므로 엔티티를 그대로 반환하지 않는다. 팬아웃 단계(무트랜잭션)에서
 * 필요한 값(파트너별 요청 상품 DTO)만 담아 반환한다 - 엔티티 참조를 넘기면 트랜잭션 밖에서
 * LazyInitializationException 또는 detached 엔티티를 잘못 재사용하는 문제가 생길 수 있다.</p>
 */
public record PreparedFanout(
        long inquiryId,
        Map<PartnerCode, List<RequestProduct>> requestProductMap
) {
}
