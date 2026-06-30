package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 한도조회 요청 서비스
 *
 * <p>FE로부터 한도조회 요청을 수신하여 유효성 검증, 금융사 선정, Inquiry 저장을
 * 수행하고 202 Accepted를 즉시 반환한다. 실제 금융사 API 전송은
 * {@link LoanLimitSenderService}가 비동기로 처리한다.</p>
 *
 * <h3>도메인 설계 원칙</h3>
 * <ul>
 *     <li>{@code PartnerCode(Enum)}: Adaptor 분기 및 컴파일 타임 타임 안정성 담당</li>
 *     <li>{@code Partner(Entity)}: 노출명, 활성화 여부 등 운영 중 변경 가능한 메타데이터를 DB로 관리</li>
 * </ul>
 *
 * <h3>비동기 처리 흐름</h3>
 * <pre>
 *     FE → requestCompareLoan() → 202 Accepted
 *                              ↓ Spring 이벤트 발행
 *                      LoanLimitInquiryCreatedEvent
 *                              ↓ @TransactionalEventListener(AFTER_COMMIT)
 *                      LoanLimitSenderService.handleInquiryCreated()
 *                              ↓ @Async("loanLimitExecutor")
 *                      금융사 API 병렬 전송
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
    private final LoanLimitStrategyFactory strategyFactory;

    private final LoReqtNoGenerator generator;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RedissonClient redissonClient;

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
    @Transactional
    public LoanLimitInquiryResponse requestCompareLoan(LoanLimitRequest request) {
        // Redis 분산 락으로 중복 요청 방어
        String lockKey = "loan:request:lock:" + request.userId() + ":" + request.loanType();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if( !lock.tryLock(0, 5, TimeUnit.SECONDS) ) {
                throw new InvalidRequestException("요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
            }

            // 락 획득 후 중복 체크
            // 진행 중인 조회가 있으면 중복 요청 방지 (당일 동일 유형 재조회 제한)
            boolean hasInProgress = loanLimitInquiryRepository.existsByUserIdAndLoanTypeAndStatus(
                    request.userId(),
                    request.loanType(),
                    InquiryStatus.IN_PROGRESS
            );
            if(hasInProgress) {
                throw new InvalidRequestException("진행 중인 한도조회가 있습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InvalidRequestException("요청 처리 중 오류가 발생했습니다.");
        } finally {
            if(lock.isHeldByCurrentThread())
                lock.unlock();
        }

        LoanLimitStrategy strategy = strategyFactory.getStrategy(request.loanType());
        // 유효성 검증 (각 상품 type 별)
        strategy.validate(request);

        // External 데이터 조회 - Strategy가 알아서 처리
        ExternalDataContext context = strategy.requiresExternalData()
                ? strategy.fetchExternalData(request)
                : ExternalDataContext.empty();

        // Strategy: 대출 유형상 가능한 금융사(코드 레벨 고정)
        // DB      : 현재 활성화된 은행 (운영 팀이 배포 없이 제어)
        List<PartnerCode> activePartnerCodes = strategy.getSupportedBanks();
        if(activePartnerCodes.isEmpty())
            throw new InvalidRequestException("현재 조회 가능한 금융사가 없습니다");

        // 외부 데이터 실패 시 진행 가능한 금융사만 필터링
        List<PartnerCode> availablePartnerCodes = strategy.filterAvailablePartners(activePartnerCodes, context);
        if(availablePartnerCodes.isEmpty()) {
            throw new InvalidRequestException(
                    "현재 조회 가능한 금융사가 없습니다. " +
                    context.errors().values().stream()
                            .map(ExternalDataError::message)
                            .collect(Collectors.joining(", "))
            );
        }

        // LoanLimitInquiry INSERT: 조회 식별번호(inquiryNo) 채번 후 저장
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .inquiryNo(generator.generate("LL"))
                .userId(request.userId())
                .name(request.name())
                .ci(request.ci())
                .jobType(request.jobType())
                .jobName(request.jobName())
                .joinDate(request.joinDate())
                .loanType(request.loanType())
                .carNo(request.carNo())
                .agreePersonalCreditInfo(request.agreePersonalCreditInfo())
                .agreePersonalCreditTime(request.agreePersonalCreditTime())
                .build();

        loanLimitInquiryRepository.save(inquiry);

        // 어댑터 요청 DTO 변환 (Strategy)
        // 대출 유형별 전략으로 금융사 전송용 요청 DTO 생성
        LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request, context);

        // 트랜잭션 커밋 후 비동기 전송을 위해 Spring 이벤트를 발행한다.
        // AFTER_COMMIT 이후 처리를 보장하기 위해 직접 호출 대신 이벤트를 사용한다
        applicationEventPublisher.publishEvent(
                LoanLimitInquiryCreatedEvent.builder()
                        .id(inquiry.getId())
                        .activePartnerCodes(activePartnerCodes)
                        .adaptorRequest(adaptorRequest)
                        .build()
        );

        return LoanLimitInquiryResponse.from(inquiry);
    }

    @Transactional(readOnly = true)
    public LoanLimitPollingResponse getInquiryResult(String inquiryNo, Pageable pageable) {
        LoanLimitInquiry inquiry = loanLimitInquiryRepository.findByInquiryNo(inquiryNo)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 조회이력입니다: " + inquiryNo));

        Page<LoanLimitProductResultDto> productResults = inquiry.isAllResultReceived()
                ? loanLimitProductResultRepository.findProductResultsByInquiryId(inquiry.getId(), pageable)
                : Page.empty(pageable);

        return LoanLimitPollingResponse.from(inquiry, productResults);
    }
}
