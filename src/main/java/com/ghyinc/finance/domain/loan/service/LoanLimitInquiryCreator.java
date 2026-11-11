package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.LoanLimitInquiryResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.PartnerDispatchPlan;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LoanLimitInquiryCreator {
    private final LoanLimitInquiryPersistenceService persistenceService;
    private final LoanLimitInquiryClaimService claimService;
    private final LoanLimitDispatchPlanner dispatchPlanner;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;

    /**
     * 1차: 기존 흐름. 마지막 INSERT에서 unique가 최후 방어선
     */
    public LoanLimitInquiryResponse processWithRedisLock(LoanLimitRequest request, String dedupKey) {
        PartnerDispatchPlan prepared = dispatchPlanner.plan(request);
        try {
            return persistenceService.createLoanLimitInquiry(request, prepared.availablePartnerCodes(), prepared.adaptorRequest(), dedupKey);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicate(dedupKey, e);
        }
    }

    /**
     * 2차: 맨 앞단 선점 → 외부 조회 → 완료. 실패하면 슬롯 반납
     */
    public LoanLimitInquiryResponse processWithDbClaim(LoanLimitRequest request, String dedupKey) {
        String inquiryNo;
        try {
            inquiryNo = claimService.claim(request.userId(), request.loanType(), dedupKey);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicate(dedupKey, e);
        }

        try {
            PartnerDispatchPlan prepared = dispatchPlanner.plan(request);
            return persistenceService.completeInquiry(inquiryNo, request, prepared.availablePartnerCodes(), prepared.adaptorRequest());
        } catch (RuntimeException e) {
            try {
                claimService.abort(inquiryNo);
            } catch (Exception abortEx) {
                e.addSuppressed(abortEx);   // 스윕퍼가 정리한다
            }
            throw e;
        }
    }

    private RuntimeException translateDuplicate(String dedupKey, DataIntegrityViolationException e) {
        return loanLimitInquiryRepository.existsByDedupKey(dedupKey)
                ? new InvalidRequestException("오늘은 이미 한도조회를 진행했습니다.")
                : e;    // inquiryNo 충돌 등 다른 제약 위반은 숨기지 않는다
    }
}
