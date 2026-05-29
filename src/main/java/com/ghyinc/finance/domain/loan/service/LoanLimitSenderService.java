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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("loanLimitExecutor")
    public void handleInquiryCreated(LoanLimitInquiryCreatedEvent event) {
        this.inquiry(event.id(), event.activePartnerCodes(), event.adaptorRequest());
    }

    /**
     * 여러 금융사에 대한 한도조회
     *
     * <p> 각 은행 API 호출은 독립적이므로 CompletableFuture로 병렬 처리
     * 한 금융사의 실패가 다른 금융사 조회에 영향을 주지 않음.
     * 전용 스레드 풀을 사용하여 외부 I/O가 공통 스레드 풀을 점유하지 않도록 격리
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
            // partnerCode별 requestProducts 구성 후 병렬 호출
            var futures = partnerCodes.stream()
                    .map(partnerCode -> {
                        //requestProducts를 포함한 요청 DTO 재구성
                        LoanLimitAdaptorRequest adaptorRequests = adaptorRequest.toBuilder()
                                .requestProducts(requestProductMap.get(partnerCode))
                                .build();

                        LoanLimitAdaptor adaptor = adaptorFactory.getAdaptor(partnerCode);
                        return CompletableFuture
                                .supplyAsync(() -> adaptor.inquireLimit(partnerCode, adaptorRequests), partnerApiExecutor)
                                .orTimeout(8, TimeUnit.SECONDS)
                                .exceptionally(ex -> {
                                    // Circuit Breaker OPEN 시
                                    if(ex.getCause() instanceof CallNotPermittedException) {
                                        log.warn("[{}] Circuit Breaker OPEN - 해당 금융사 격리", partnerCode, ex);
                                        return LoanLimitAdaptorResponse.fail(partnerCode, ex.getMessage(), 0L);
                                    }

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

            List<LoanLimitAdaptorResponse> adaptorResponses = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            // 어댑터 응답을 후처리하고 Entity로 변환하여 저장
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

            // 최종 상태 결정
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
            log.error("한도조회 처리 중 오류. id={}", loanLimitInquiry.getId(), e);
            loanLimitInquiry.updateInquiryStatus(InquiryStatus.FAILED);
        }
    }
}
