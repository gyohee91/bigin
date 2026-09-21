package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.PreparedFanout;
import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

/**
 * 한도조회 비동기 전송 서비스
 *
 * <p>금융사별 한도조회 API를 병렬로 전송하고 전송 결과를 기반으로 Inquiry 상태를
 *      결정한다. 전송 완료 후 알림 발송을 위한 Outbox 이벤트를 저장한다</p>
 *
 * <h3>비동기 처리 구조</h3>
 * <ul>
 *     <li>{@code handleInquiryCreated()}:
 *          {@code @TransactionEventListener(AFTER_COMMIT)}으로
 *          부모 트랜잭션 보장 후 {@code loanLimitExecutor} 스레드에서 실행</li>
 *     <li>{@code inquiry()}: 금융사별 API를 {@code partnerApiExecutor} 스레드 풀에서 병렬 전송.
 *          스레드 풀 분리로 {@code loanLimitExecutor} DeadLock 방지</li>
 * </ul>
 *
 * <p><b>트랜잭션 경계 분리 (2026-09):</b> 이 메서드는 더 이상 {@code @Transactional}이 아니다.
 * 과거엔 선저장 → 파트너 팬아웃 대기(join) → 결과반영이 전부 하나의 트랜잭션이라, 파트너 응답을
 * 기다리는 동안(최대 partnerOrTimeout, 수 초) {@code loanLimitExecutor} 스레드가 Hikari
 * 커넥션을 계속 붙잡고 있었다. 부하가 걸려 파트너 응답이 느려질수록 커넥션 점유시간도 늘어나고,
 * 그만큼 더 많은 동시 커넥션이 필요해지는 피드백 루프로 Hikari 풀이 고갈되는 근본 원인이었다.
 * 지금은 DB 트랜잭션이 필요한 두 구간만 {@link LoanLimitInquiryPersistenceService}로 분리했고,
 * 팬아웃 대기(join) 구간은 트랜잭션/커넥션 없이 수행한다.</p>
 *
 * @see LoanLimitService
 * @see LoanLimitInquiryPersistenceService
 * @see com.ghyinc.finance.global.outbox.service.OutboxEventService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitSenderService {
    private final LoanLimitAdaptorFactory adaptorFactory;
    private final LoanLimitInquiryPersistenceService persistenceService;

    private final Executor partnerApiExecutor;
    private final Map<PartnerCode, Duration> partnerOrTimeouts;
    private final MeterRegistry meterRegistry;

    /**
     * 복수 금융사에 대한 한도조회 요청을 병렬로 처리한다.
     *
     * <h3>처리 순서</h3>
     * <ol>
     *     <li>선저장 트랜잭션(짧게, commit): LoanLimitResult/LoanLimitProductResult 선저장,
     *          Inquiry IN_PROGRESS 전이 - {@link LoanLimitInquiryPersistenceService#preSave}</li>
     *     <li>팬아웃(무트랜잭션): 금융사별 RequestProduct 구성 후 {@code partnerApiExecutor}에서
     *          병렬 전송 및 응답 대기 - 이 구간은 Hikari 커넥션을 점유하지 않는다</li>
     *     <li>결과반영 트랜잭션(짧게, 새 트랜잭션): 전송 결과 집계 → Result/ProductResult 상태
     *          UPDATE → Inquiry 최종 상태 결정 → Outbox INSERT
     *          - {@link LoanLimitInquiryPersistenceService#applyResults}</li>
     * </ol>
     *
     * <p>세 단계 중 어디서든 예상치 못한 예외가 발생하면 별도의 짧은 트랜잭션으로 Inquiry를
     * FAILED 처리한다({@link LoanLimitInquiryPersistenceService#markFailed}).</p>
     *
     * @param id                LoanLimitInquiry PK
     * @param partnerCodes      한도조회 대상 금융사 목록
     * @param adaptorRequest    금융사 전송용 공통 요청 DTO
     */
    public void inquiry(
            long id,
            List<PartnerCode> partnerCodes,
            LoanLimitAdaptorRequest adaptorRequest
    ) {
        PreparedFanout prepared;
        try {
            prepared = persistenceService.preSave(id, partnerCodes, adaptorRequest);
        } catch (Exception e) {
            log.error("한도조회 선저장 중 오류. id={}", id, e);
            persistenceService.markFailed(id);
            return;
        }

        List<LoanLimitAdaptorResponse> adaptorResponses;
        try {
            Map<PartnerCode, List<RequestProduct>> requestProductMap = prepared.requestProductMap();

            // 금융사별 병렬 API 호출
            // partnerApiExecutor(I/O 전용 스레드 풀)에서 실행하여 loanLimitExecutor 스레드를 해제한다
            var futures = partnerCodes.stream()
                    .map(partnerCode -> {
                        // 금융사별 상품 목록(requestProducts)를 포함한 요청 DTO 재구성
                        LoanLimitAdaptorRequest adaptorRequests = adaptorRequest.toBuilder()
                                .requestProducts(requestProductMap.get(partnerCode))
                                .build();

                        LoanLimitAdaptor adaptor = adaptorFactory.getAdaptor(partnerCode);
                        try {
                            return CompletableFuture
                                    .supplyAsync(() -> adaptor.inquireLimit(partnerCode, adaptorRequests), partnerApiExecutor)
                                    .orTimeout(partnerOrTimeouts.get(partnerCode).toMillis(), TimeUnit.MILLISECONDS)
                                    .exceptionally(ex -> {
                                        // Circuit Breaker OPEN: Fallback으로 즉시 실패 반환
                                        // 해당 금융사는 격리되며 나머지 금융사는 정상 진행
                                        if (ex.getCause() instanceof CallNotPermittedException) {
                                            log.warn("[{}] Circuit Breaker OPEN - 해당 금융사 격리", partnerCode, ex);
                                            return LoanLimitAdaptorResponse.fail(partnerCode, "CB_OPEN", 0L);
                                        }

                                        // RateLimiter 한도 초과 Fallback 추가
                                        if (ex.getCause() instanceof RequestNotPermitted) {
                                            log.warn("[{}] RateLimiter 한도 초과 - 요청 제한", partnerCode);
                                            return LoanLimitAdaptorResponse.fail(partnerCode, "RATE_LIMIT_EXCEEDED", 0L);
                                        }

                                        // Bulkhead 동시 호출 한도 초과 Fallback 추가
                                        if (ex.getCause() instanceof BulkheadFullException) {
                                            log.warn("[{}] Bulkhead 포화 - 동시 호출 한도 초과", partnerCode);
                                            return LoanLimitAdaptorResponse.fail(partnerCode, "BULKHEAD_FULL", 0L);
                                        }

                                        log.error("[{}] 비동기 한도조회 중 에러 발생", partnerCode, ex);
                                        return LoanLimitAdaptorResponse.fail(partnerCode, ex.getMessage(), 0L);
                                    });

                        } catch (RejectedExecutionException e) {
                            // 제출 시점 거절 - supplyAsync()가 CompletableFuture에 반환하기 전에
                            // ThreadPoolExecutor.execute()에서 동기적으로 발생하므로 위 exceptionally()로는 못 잡음.
                            // 여기서 안 잡으면 예외가 map() 밖으로 튀어나가 전체 요청이 FAILED 처리된다.
                            log.error("[{}] partnerApiExecutor 큐 초과 (제출 시점)", partnerCode, e);
                            // 파트너별로 큐 초과가 얼마나 자주 나는지, 임계값(maxPoolSize + queueCapacity)이 실제로 얼마나 자주 근접/초과하는지 대시보드로 확인
                            meterRegistry.counter("partner.api.executor.rejected", "partner", partnerCode.name());
                            return CompletableFuture.completedFuture(
                                    LoanLimitAdaptorResponse.fail(partnerCode, "THREAD_POOL_EXHAUSTED", 0L)
                            );
                        }
                    })
                    .toList();

            // 모든 금융사 응답을 수집한다. (join()은 각 Future의 orTimeout 내에서 대기)
            // 이 구간은 트랜잭션/Hikari 커넥션 없이 대기한다 - 파트너 응답이 아무리 느려져도
            // DB 커넥션 풀에는 영향이 없다.
            adaptorResponses = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();
        } catch (Exception e) {
            log.error("한도조회 파트너 전송 중 오류. id={}", id, e);
            persistenceService.markFailed(prepared.inquiryId());
            return;
        }

        try {
            persistenceService.applyResults(prepared.inquiryId(), adaptorResponses);
        } catch (Exception e) {
            log.error("한도조회 처리 중 오류. id={}", prepared.inquiryId(), e);
            persistenceService.markFailed(prepared.inquiryId());
        }
    }
}
