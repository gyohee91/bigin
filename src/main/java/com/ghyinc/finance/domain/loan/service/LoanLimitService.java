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
 * <p>FE로부터 한도조회 요청을 수신하여 유효성 검증, 금융사 선정, Inquiry 저장을
 * 수행하고 즉시 응답한다. 실제 금융사 API 전송은 트랜잭션 커밋 후 이벤트를 통해
 * {@link LoanLimitEventHandler}와 {@link LoanLimitSenderService}가 비동기로 처리한다.</p>
 *
 * <h3>도메인 설계 원칙</h3>
 * <ul>
 *     <li>{@code PartnerCode(Enum)}: Adaptor 분기 및 컴파일 타임 타임 안정성 담당</li>
 *     <li>{@code Partner(Entity)}: 노출명, 활성화 여부 등 운영 중 변경 가능한 메타데이터를 DB로 관리</li>
 * </ul>
 *
 * <h3>비동기 처리 흐름</h3>
 * <pre>
 *  FE → requestCompareLoan()   [Redis 락: userId + loanType]
 *         ├ 중복 체크 · 검증 · 외부 데이터 조회 · 금융사 선정
 *         └ persistenceService.createLoanLimitInquiry()   [Tx0: INSERT 후 커밋]
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
     *     <li>진행 중 중복 요청 방지 (동일 userId + loanType)</li>
     *     <li>대출 유형별 Strategy 선택 및 입력값 유효성 검증</li>
     *     <li>외부 데이터 조회 (Nice DNR, KB시세 등 - Strategy 위임)</li>
     *     <li>활성화된 금융사 선정 및 외부 데이터 오류 시 금융사 필터링</li>
     *     <li>LoanLimitInquiry INSERT 후 Spring 이벤트 발행</li>
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
     * @throws InvalidRequestException 진행 중인 조회 존재, 조회 가능 금융사 없음
     */
    public LoanLimitInquiryResponse requestCompareLoan(LoanLimitRequest request) {
        String dedupKey = LoanLimitInquiry.dedupKeyOf(request.userId(), request.loanType(), LocalDate.now());
        if (loanLimitInquiryRepository.existsByDedupKey(dedupKey)) {
            throw new InvalidRequestException("오늘은 이미 한도조회를 진행했습니다.");
        }

        // Redis 분산 락으로 중복 요청 방어
        String lockKey = "loan:request:lock:" + request.userId() + ":" + request.loanType();
        try {
            // 1차: Redis 락
            return lockExecutor.execute(lockKey, 0, 15,
                    () -> inquiryCreator.processWithRedisLock(request, dedupKey),
                    () -> {
                        throw new InvalidRequestException("요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
                    });
        } catch (LockUnavailableException e) {
            // Redis 장애 시 DB 원자적 선점으로 전환
            log.warn("[한도조회] 락 불가(Redis 장애), DB 선점으로 전환 userId={}", request.userId());
            // 2차: DB 선점
            return inquiryCreator.processWithDbClaim(request, dedupKey);
        }
    }

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
