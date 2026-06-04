package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import com.ghyinc.finance.global.event.impl.KafkaLoanLimitEventPublisher;
import com.ghyinc.finance.global.event.impl.SpringLoanLimitEventPublisher;
import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 * @see LoanLimitService
 * @see com.ghyinc.finance.global.outbox.service.OutboxEventService
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitSenderService {
    private final LoanLimitAdaptorFactory adaptorFactory;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final ProductRepository productRepository;
    private final OutboxEventRepository outboxEventRepository;

    private final LoReqtNoGenerator generator;
    private final Executor partnerApiExecutor;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SpringLoanLimitEventPublisher springLoanLimitEventPublisher;
    private final KafkaLoanLimitEventPublisher kafkalLoanLimitEventPublisher;
    private final ObjectMapper objectMapper;

    private static final String REQUEST_ID_KEY = "requestId";

    /**
     * {@link LoanLimitInquiryCreatedEvent} 수신 후 한도조회 비동기 전송을 시작한다.
     *
     * <p>{@code @TransactionalEventListener(AFTER_COMMIT)}을 통해 부모 트랜잭션
     * (Inquiry INSERT)이 커밋된 이후에만 실행을 보장한다. 커밋 전 실행 시 금융사
     * API 응답(콜백)이 먼저 도착해도 Inquiry를 조회할 수 없는 Race Condition이
     * 발생할 수 있기 때문이다.</p>
     *
     * <p>{@code @Async("loanLimitExecutor")}로 HTTP 요청 스레드를 즉시 해제하여
     * FE에 202 Accepted를 반환한다.</p>
     *
     * @param event inquiryId, 금융사 목록, 어댑터 요청 DTO를 포함한 이벤트
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("loanLimitExecutor")
    public void handleInquiryCreated(LoanLimitInquiryCreatedEvent event) {
        this.inquiry(event.id(), event.activePartnerCodes(), event.adaptorRequest());
    }

    /**
     * 복수 금융사에 대한 한도조회 요청을 병렬로 처리한다.
     *
     * <h3>처리 순서</h3>
     * <ol>
     *     <li>LoanLimitResult 선저장 (금융사당 1건)</li>
     *     <li>Product 조회 → 신청번호 채번 → LoanLimitProductResult 선저장 (상품당 1건)</li>
     *     <li>금융사별 RequestProduct 구성 후 {@code partnerApiExecutor}에서 병렬 전송</li>
     *     <li>전송 결과 집계 → Result/ProductResult 상태 UPDATE</li>
     *     <li>Inquiry 최종 상태 결정 (SUCCESS / PARTIAL_SUCCESS / FAILED)</li>
     *     <li>Outbox INSERT → Spring 이벤트 발행 (알림 발송 트리거)</li>
     * </ol>
     *
     * @param id                LoanLimitInquiry PK
     * @param partnerCodes      한도조회 대상 금융사 목록
     * @param adaptorRequest    금융사 전송용 공통 요청 DTO
     */
    @Transactional
    public void inquiry(
            long id,
            List<PartnerCode> partnerCodes,
            LoanLimitAdaptorRequest adaptorRequest
    ) {
        // 새 트랜잭션에서 inquiry 조회 (호출 측 트랜잭션과 완전 분리)
        LoanLimitInquiry loanLimitInquiry = loanLimitInquiryRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 조회 이력: " + id));

        loanLimitInquiry.updateInquiryStatus(InquiryStatus.IN_PROGRESS);

        try {
            // 각 금융사에 대한 Result 선저장
            // partnerCode -> 해당 금융사 코드
            Map<PartnerCode, LoanLimitResult> resultMap = partnerCodes.stream()
                    .collect(Collectors.toMap(
                            partnerCode -> partnerCode,
                            partnerCode -> {
                                LoanLimitResult result = LoanLimitResult.builder()
                                        .loanLimitInquiry(loanLimitInquiry)
                                        .partnerCode(partnerCode)
                                        .build();
                                loanLimitInquiry.addResult(result);
                                return result;
                            }
                    ));

            // 금융사별 상품 조회 및 ProductResult 선저장
            Map<PartnerCode, List<LoanLimitProductResult>> productResultMap = partnerCodes.stream()
                    .collect(Collectors.toMap(
                            partnerCode -> partnerCode,
                            partnerCode -> productRepository.findActiveByPartnerCodeAndLoanType(partnerCode, adaptorRequest.loanType())
                                    .stream()
                                    .map(product -> {
                                        LoanLimitProductResult productResult =
                                                LoanLimitProductResult.builder()
                                                        .loanLimitInquiry(loanLimitInquiry)
                                                        .loReqtNo(generator.generate("LR")) //신청번호 채번
                                                        .partnerCode(partnerCode)
                                                        .productCode(product.getProductCode())
                                                        .status(PartnerInquiryStatus.PENDING)
                                                        .build();
                                        loanLimitInquiry.addProductResult(productResult);
                                        return productResult;
                                    }).toList()
                    ));

            // 상품 전체 수 초기화
            int totalProductCount = productResultMap.values().stream()
                    .mapToInt(List::size)
                    .sum();
            loanLimitInquiry.initProductCount(totalProductCount);

            // 금융사별 RequestProduct(공통 요청 DTO) 구성
            Map<PartnerCode, List<RequestProduct>> requestProductMap = productResultMap.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().stream()
                                    .map(productResult ->
                                            RequestProduct.builder()
                                                    .loReqtNo(productResult.getLoReqtNo())
                                                    .productCode(productResult.getProductCode())
                                                    .build()
                                    ).toList()
                    ));


            // 금융사별 병렬 API 호출
            // partnerApiExecutor(I/O 전용 스레드 풀)에서 실행하여 loanLimitExecutor 스레드를 해제한다
            var futures = partnerCodes.stream()
                    .map(partnerCode -> {
                        // 금융사별 상품 목록(requestProducts)를 포함한 요청 DTO 재구성
                        LoanLimitAdaptorRequest adaptorRequests = adaptorRequest.toBuilder()
                                .requestProducts(requestProductMap.get(partnerCode))
                                .build();

                        LoanLimitAdaptor adaptor = adaptorFactory.getAdaptor(partnerCode);
                        return CompletableFuture
                                .supplyAsync(() -> adaptor.inquireLimit(partnerCode, adaptorRequests), partnerApiExecutor)
                                .orTimeout(8, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    // Circuit Breaker OPEN: Fallback으로 즉시 실패 반환
                                    // 해당 금융사는 격리되며 나머지 금융사는 정상 진행
                                    if(ex.getCause() instanceof CallNotPermittedException) {
                                        log.warn("[{}] Circuit Breaker OPEN - 해당 금융사 격리", partnerCode, ex);
                                        return LoanLimitAdaptorResponse.fail(partnerCode, ex.getMessage(), 0L);
                                    }

                                    // partnerApiExecutor 큐 초과 시 즉시 실패 처리
                                    if (ex.getCause() instanceof RejectedExecutionException) {
                                        log.error("[{}] partnerApiExecutor 큐 초과", partnerCode);
                                        return LoanLimitAdaptorResponse.fail(
                                                partnerCode, "THREAD_POOL_EXHAUSTED", 0L);
                                    }

                                    log.error("[{}] 비동기 한도조회 중 에러 발생", partnerCode, ex);
                                    return LoanLimitAdaptorResponse.fail(partnerCode, ex.getMessage(), 0L);
                                });
                    })
                    .toList();

            // 모든 금융사 응답을 수집한다. (join()은 각 Future의 orTimeout 내에서 대기)
            List<LoanLimitAdaptorResponse> adaptorResponses = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // 전송 결과에 따라 Result / ProductResult 상태 UPDATE
            // 성공: SEND_SUCCESS, 실패: SEND_FAILED (콜백 대기 여부 결정)
            adaptorResponses.forEach(adaptorResponse -> {
                LoanLimitResult result = resultMap.get(adaptorResponse.partnerCode());

                    if(adaptorResponse.success()) {
                        result.success(adaptorResponse.resTimeMs());
                        productResultMap.get(adaptorResponse.partnerCode())
                                .forEach(LoanLimitProductResult::sendSuccess);
                    }
                    else {
                        result.fail(
                                adaptorResponse.failReason(),
                                adaptorResponse.resTimeMs()
                        );

                        productResultMap.get(adaptorResponse.partnerCode())
                                .forEach(LoanLimitProductResult::sendFail);
                    }

            });

            // 성공 금융사 수에 따라 Inquiry 최종 상태 결정
            // 전체 성공: SUCCESS, 전체 실패: FAILED, 일부 성공: PARTIAL_SUCCESS
            long successCount = adaptorResponses.stream()
                    .filter(LoanLimitAdaptorResponse::success).count();
            InquiryStatus resultStatus = successCount == adaptorResponses.size()
                    ? InquiryStatus.SUCCESS
                    : (successCount == 0 ? InquiryStatus.FAILED : InquiryStatus.PARTIAL_SUCCESS);

            loanLimitInquiry.updateInquiryStatus(resultStatus);

            // 알림 발송 - notification 도메인을 직접 알지 못함
            if(!Objects.equals(InquiryStatus.FAILED, resultStatus)) {
                // Outbox INSERT (비즈니스 트랜잭션과 원자적)
                OutboxEvent outboxEvent = OutboxEvent.builder()
                        .aggregateType("LoanLimitInquiry")
                        .aggregateId(loanLimitInquiry.getInquiryNo())
                        .eventType("LOAN_LIMIT_COMPLETED")
                        .payload(objectMapper.writeValueAsString(
                                LoanLimitCompletedEvent.builder()
                                        .inquiryNo(loanLimitInquiry.getInquiryNo())
                                        .userId(loanLimitInquiry.getUserId())
                                        .name(loanLimitInquiry.getName())
                                        .status(loanLimitInquiry.getStatus())
                                        // MDC requestId를 payload에 포함하여 Kafka Consumer 스레드에서 복원
                                        .requestId(MDC.get(REQUEST_ID_KEY))
                                        .build()
                        ))
                        .status(OutboxStatus.PENDING)
                        .build();

                outboxEventRepository.save(outboxEvent);

                //kafkalLoanLimitEventPublisher.publishCompletedEvent(event);
                //springLoanLimitEventPublisher.publishCompletedEvent(event);

                // Spring 이벤트 발행 (트랜잭션 커밋 후 Kafka 발행 트리거)
                applicationEventPublisher.publishEvent(
                        new OutboxCreatedEvent(outboxEvent.getId()));
            }

        } catch(Exception e) {
            // 예상치 못한 예외 발생 시 Inquiry를 FAILED로 처리한다.
            log.error("한도조회 처리 중 오류. id={}", loanLimitInquiry.getId(), e);
            loanLimitInquiry.updateInquiryStatus(InquiryStatus.FAILED);
        }
    }
}
