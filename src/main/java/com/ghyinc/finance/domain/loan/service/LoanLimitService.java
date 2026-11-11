package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import com.ghyinc.finance.global.exception.LockUnavailableException;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 한도조회 요청 서비스
 *
 * <p>FE로부터 한도조회 요청을 수신하여 중복 요청을 방어하고, 유효성 검증 · 금융사 선정 · Inquiry 저장은
 * {@link LoanLimitInquiryCreator}에 위임한 뒤 즉시 응답한다. 이 클래스는 "어떤 방식으로 중복을 막을지"만
 * 결정하는 오케스트레이터이며, 실제 금융사 API 전송은 트랜잭션 커밋 후 이벤트를 통해
 * {@link LoanLimitEventHandler}와 {@link LoanLimitSenderService}가 비동기로 처리한다.</p>
 *
 * <h3>중복 요청 방어 (하루 1회, 2단계)</h3>
 * <ol>
 *     <li><b>사전 조회</b>: {@code dedup_key}({@code userId:loanType:yyyyMMdd})가 이미 존재하면 즉시 거절.
 *         락·외부 조회 비용을 쓰기 전에 걸러내는 빠른 경로이며, 정합성은 아래 unique 제약이 보장한다.</li>
 *     <li><b>1차 - Redis 분산 락</b>: 평상시 경로. 동일 사용자·유형의 동시 요청을 대기 없이 차단한다.</li>
 *     <li><b>2차 - DB 선점</b>: Redis 장애로 락을 잡을 수 없을 때({@link LockUnavailableException})만 사용.
 *         외부 조회 전에 ACCEPTED 행을 먼저 INSERT 하여 {@code dedup_key} unique 제약으로 선점한다.</li>
 * </ol>
 * <p>어느 경로든 최종 방어선은 {@code dedup_key} unique 제약이다. 락은 외부 조회 비용을 아끼기 위한 최적화다.</p>
 *
 * <h3>도메인 설계 원칙</h3>
 * <ul>
 *     <li>{@code PartnerCode(Enum)}: Adaptor 분기 및 컴파일 타임 타임 안정성 담당</li>
 *     <li>{@code Partner(Entity)}: 노출명, 활성화 여부 등 운영 중 변경 가능한 메타데이터를 DB로 관리</li>
 * </ul>
 *
 * <h3>비동기 처리 흐름</h3>
 * <pre>
 *  FE → requestCompareLoan()
 *         ├ dedup_key 사전 조회 (존재하면 거절)
 *         ├ 1차 [Redis 락: userId + loanType] inquiryCreator.processWithRedisLock()
 *         │     ├ dispatchPlanner.plan()   검증 · 외부 데이터 조회 · 금융사 선정
 *         │     └ persistenceService.createLoanLimitInquiry()   [Tx0: INSERT(PENDING) 후 커밋]
 *         └ 2차 [Redis 장애 시] inquiryCreator.processWithDbClaim()
 *               ├ claimService.claim()   [REQUIRES_NEW: ACCEPTED INSERT 로 선점]
 *               ├ dispatchPlanner.plan()   검증 · 외부 데이터 조회 · 금융사 선정
 *               ├ persistenceService.completeInquiry()   [Tx0: ACCEPTED→PENDING 후 커밋]
 *               └ 실패 시 claimService.abort()   [ACCEPTED→FAILED, 슬롯 반납]
 *               ↓ LoanLimitInquiryCreatedEvent 발행
 *  LoanLimitEventHandler#handleInquiryCreated()
 *         @TransactionalEventListener(AFTER_COMMIT)
 *               ↓ loanLimitExecutor.execute()
 *                 (포화 시 TaskRejectedException → compensationExecutor로 FAILED 전환)
 *  LoanLimitSenderService#inquiry()   [loanLimitExecutor 스레드]
 *         preSave(Tx1) → 금융사 Fan-out(무트랜잭션, partnerApiExecutor) → applyResults(Tx2)
 * </pre>
 *
 * @see LoanLimitSenderService
 * @see LoanLimitStrategy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitService {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;
    private final RedisLockExecutor lockExecutor;
    private final LoanLimitInquiryCreator inquiryCreator;

    /**
     * 비교대출 한도조회 요청을 처리한다.
     *
     * <p>요청 수신 즉시 Inquiry를 저장하고 202 Accepted를 반환한다.
     *      실제 금융사 API 전송은 트랜잭션 커밋 후 {@code LoanLimitInquiryCreatedEvent}
     *      를 통해 비동기로 위임한다</p>
     *
     * <h3>처리 순서</h3>
     * <ol>
     *     <li>당일 중복 조회 방지: {@code dedup_key} 존재 여부 사전 확인 (있으면 거절)</li>
     *     <li>1차 Redis 락(userId + loanType, 대기 0초)을 잡고 {@link LoanLimitInquiryCreator#processWithRedisLock} 실행.
     *         락을 못 잡으면(= 이미 처리 중) "처리 중" 오류로 거절</li>
     *     <li>Redis 장애로 락 자체가 불가하면 2차로 {@link LoanLimitInquiryCreator#processWithDbClaim} 실행
     *         (DB 선점 → 계획 수립 → 완료, 실패 시 선점 반납)</li>
     *     <li>두 경로 모두 {@link LoanLimitDispatchPlanner#plan}으로 Strategy 선택 · 입력값 검증 ·
     *         외부 데이터 조회 · 금융사 선정을 수행하고, 완료 시 Spring 이벤트를 발행</li>
     * </ol>
     *
     * <h3>금융사 선정 이중 구조</h3>
     * <ul>
     *     <li>{@code getSupportedBanks()}: Strategy 레벨에서 대출 유형별 지원 금융사 고정 관리</li>
     *     <li>{@code filterAvailablePartners()}: 외부 데이터 조회 실패 시 해당 금융사 동적 제외</li>
     * </ul>
     *
     * @param request 한도조회 요청 DTO
     * @return 202 Accepted 응답 (inquiryNo 포함)
     * @throws InvalidRequestException 당일 이미 조회함(dedup_key 중복), 동일 요청 처리 중(락 획득 실패),
     *                                 조회 가능 금융사 없음, 입력값 검증 실패
     */
    public LoanLimitInquiryResponse requestCompareLoan(LoanLimitRequest request) {
        String dedupKey = LoanLimitInquiry.dedupKeyOf(request.userId(), request.loanType(), LocalDate.now());
        if (loanLimitInquiryRepository.existsByDedupKey(dedupKey)) {
            throw new InvalidRequestException("오늘은 이미 한도조회를 진행했습니다.");
        }

        // 1차 방어: Redis 분산 락 (대기 0초 - 이미 처리 중이면 기다리지 않고 거절)
        String lockKey = "loan:request:lock:" + request.userId() + ":" + request.loanType();
        try {
            // 1차: Redis 락
            return lockExecutor.execute(lockKey, 0, 15,
                    () -> inquiryCreator.processWithRedisLock(request, dedupKey),
                    () -> {
                        throw new InvalidRequestException("요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
                    });
        } catch (LockUnavailableException e) {
            // Redis 장애(락 획득 단계의 RedisException)일 때만 여기로 온다.
            // "이미 처리 중"(락 획득 실패)은 위 onLockUnavailable 콜백에서 거절되므로 구분된다.
            // → dedup_key unique 제약을 이용한 DB 원자적 선점으로 전환 (fail-closed 대신 DB 로 대체)
            log.warn("[한도조회] 락 불가(Redis 장애), DB 선점으로 전환 userId={}", request.userId());
            // 2차: DB 선점
            return inquiryCreator.processWithDbClaim(request, dedupKey);
        }
    }

    /**
     * FE 폴링용 한도조회 결과를 반환한다.
     *
     * <p>진행 중에도 현재까지 수신된 금융사별 결과를 함께 내려, FE가 점진적으로 표시할 수 있다.</p>
     *
     * @param inquiryNo 조회 식별번호
     * @param pageable  상품 결과 페이징
     * @throws InvalidRequestException 존재하지 않는 inquiryNo
     */
    @Transactional(readOnly = true)
    public LoanLimitPollingResponse getInquiryResult(String inquiryNo, Pageable pageable) {
        LoanLimitInquiry inquiry = loanLimitInquiryRepository.findByInquiryNo(inquiryNo)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 조회이력입니다: " + inquiryNo));

        // 진행 중(콜백 일부 수신)에도 현재까지 수신된 금융사별 결과 목록을 함께 반환한다.
        // FE는 폴링할 때마다 이 목록으로 어느 금융사가 결과를 보내왔는지 점진적으로 표시한다.
        Page<LoanLimitProductResultDto> productResults =
                loanLimitProductResultRepository.findProductResultsByInquiryId(inquiry.getId(), pageable);

        return LoanLimitPollingResponse.from(inquiry, productResults);
    }
}
