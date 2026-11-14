package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.LoanLimitInquiryResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.PartnerDispatchPlan;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 한도조회 Inquiry 생성 전략 모음. {@link LoanLimitService}가 중복 방어 방식에 따라 둘 중 하나를 호출한다.
 *
 * <p>두 경로 모두 "하루 1회" 제약은 {@code dedup_key} unique 제약이 최종 보장한다.
 * 차이는 unique 제약을 <b>언제</b> 사용하느냐다.</p>
 *
 * <ul>
 *     <li>{@link #processWithRedisLock}: Redis 락 보호 하에 계획 수립 후 마지막에 INSERT (평상시)</li>
 *     <li>{@link #processWithDbClaim}: 맨 앞에서 ACCEPTED 행을 INSERT 하여 선점한 뒤 계획 수립 (Redis 장애 시).
 *         중복이면 외부 조회 비용을 쓰기 전에 즉시 거절된다.</li>
 * </ul>
 *
 * <p>트랜잭션 경계가 서로 다른 협력 빈({@link LoanLimitInquiryClaimService},
 * {@link LoanLimitInquiryPersistenceService})을 조합하므로, 이 클래스 자체는 트랜잭션을 열지 않는다.
 * (외부 데이터 조회 동안 DB 커넥션을 점유하지 않기 위함)</p>
 */
@Component
@RequiredArgsConstructor
public class LoanLimitInquiryCreator {
    private final LoanLimitInquiryPersistenceService persistenceService;
    private final LoanLimitInquiryClaimService claimService;
    private final LoanLimitDispatchPlanner dispatchPlanner;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;

    /**
     * 1차 경로(Redis 락 보유 상태에서 호출): 계획 수립 → Inquiry INSERT(PENDING) → 이벤트 발행.
     *
     * <p>락이 동시 요청을 막아주므로 평소엔 unique 위반이 나지 않는다. 락 만료 등으로 새어 들어온
     * 요청은 마지막 INSERT 의 {@code dedup_key} unique 제약이 최후 방어선으로 걸러낸다.</p>
     *
     * @throws InvalidRequestException 당일 중복 조회, 조회 가능 금융사 없음, 입력값 검증 실패
     */
    public LoanLimitInquiryResponse processWithRedisLock(LoanLimitRequest request, String dedupKey) {
        PartnerDispatchPlan plan = dispatchPlanner.plan(request);
        try {
            return persistenceService.createLoanLimitInquiry(request, plan.availablePartnerCodes(), plan.adaptorRequest(), dedupKey);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicate(dedupKey, e);
        }
    }

    /**
     * 2차 경로(Redis 장애 시): 선점 → 계획 수립 → 완료. 실패하면 선점한 슬롯을 반납한다.
     *
     * <ol>
     *     <li>{@code claim}: ACCEPTED 행을 별도 트랜잭션으로 INSERT·커밋 (unique 로 선점).
     *         중복이면 외부 조회 전에 여기서 바로 거절된다.</li>
     *     <li>{@code plan}: 검증 · 외부 데이터 조회 · 금융사 선정 (DB 트랜잭션 밖)</li>
     *     <li>{@code completeInquiry}: ACCEPTED→PENDING 전이 + 요청 정보 반영 + 이벤트 발행</li>
     *     <li>2~3단계에서 예외가 나면 {@code abort}: ACCEPTED→FAILED, {@code dedup_key} 해제 (당일 재시도 허용)</li>
     * </ol>
     *
     * @throws InvalidRequestException 당일 중복 조회, 조회 가능 금융사 없음, 입력값 검증 실패,
     *                                 처리 시간 초과로 선점이 이미 정리된 경우
     */
    public LoanLimitInquiryResponse processWithDbClaim(LoanLimitRequest request, String dedupKey) {
        String inquiryNo;
        try {
            inquiryNo = claimService.claim(request.userId(), request.loanType(), dedupKey);
        } catch (DataIntegrityViolationException e) {
            throw translateDuplicate(dedupKey, e);
        }

        try {
            PartnerDispatchPlan plan = dispatchPlanner.plan(request);
            return persistenceService.completeInquiry(inquiryNo, request, plan.availablePartnerCodes(), plan.adaptorRequest());
        } catch (RuntimeException e) {
            try {
                claimService.abort(inquiryNo);
            } catch (Exception abortEx) {
                // 반납 자체가 실패하면 ACCEPTED 로 남는다. 원인 예외를 가리지 않도록 suppressed 로만 붙이고,
                // 남은 건은 장애 복구(stale 배치) 대상이다.
                e.addSuppressed(abortEx);
            }
            throw e;
        }
    }

    /**
     * unique 위반이 "당일 중복"인지 판정해 사용자용 예외로 변환한다.
     *
     * <p>{@code dedup_key} 가 실제로 존재할 때만 중복으로 본다. inquiryNo 충돌 등 다른 제약 위반은
     * 중복으로 오인해 숨기지 않도록 원본 예외를 그대로 돌려준다.</p>
     */
    private RuntimeException translateDuplicate(String dedupKey, DataIntegrityViolationException e) {
        return loanLimitInquiryRepository.existsByDedupKey(dedupKey)
                ? new InvalidRequestException("오늘은 이미 한도조회를 진행했습니다.")
                : e;    // inquiryNo 충돌 등 다른 제약 위반은 숨기지 않는다
    }
}
