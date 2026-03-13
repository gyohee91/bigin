package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitSenderService {
    private final LoanLimitAdaptorFactory adaptorFactory;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final ProductRepository productRepository;

    private final LoReqtNoGenerator generator;
    private final Executor loanLimitExecutor;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 여러 금융사에 대한 한도조회
     *
     * <p> 각 은행 API 호출은 독립적이므로 CompletableFuture로 병렬 처리
     * 한 금융사의 실패가 다른 금융사 조회에 영향을 주지 않음.
     * 전용 스레드 풀을 사용하여 외부 I/O가 공통 스레드 풀을 점유하지 않도록 격리
     * @param partnerCodes
     * @param adaptorRequest
     * @return
     */
    @Async("loanLimitExecutor")
    @Transactional
    public void inquiry(
            long id,
            List<PartnerCode> partnerCodes,
            LoanLimitAdaptorRequest adaptorRequest,
            LoanLimitStrategy strategy
    ) {
        //새 트랜잭션에서 inquiry 조회 (호출 측 트랜잭션과 완전 분리)
        LoanLimitInquiry loanLimitInquiry = loanLimitInquiryRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 조회 이력: " + id));

        loanLimitInquiry.updateInquiryStatus(InquiryStatus.IN_PROGRESS);

        try {
            //금융사별 상품 조회 및 loReqtNo 채번 후 Result 선저장
            //partnerCode -> 해당 금융사의 활성 상품 목록 조회
            Map<PartnerCode, LoanLimitResult> resultMap = partnerCodes.stream()
                    .collect(Collectors.toMap(
                            partnerCode -> partnerCode,
                            partnerCode -> {
                                LoanLimitResult result = LoanLimitResult.builder()
                                        .loReqtNo(generator.generate())
                                        .partnerCode(partnerCode)
                                        .build();
                                loanLimitInquiry.addResult(result);
                                return result;
                            }
                    ));


            //금융사별 병렬 API 호출
            //partnerCode별 requestProducts 구성 후 병렬 호출
            List<CompletableFuture<LoanLimitAdaptorResponse>> futures = partnerCodes.stream()
                    .map(partnerCode -> {
                        List<RequestProduct> requestProducts = productRepository.findActiveByPartnerCode(partnerCode)
                                .stream()
                                .map(result -> RequestProduct.builder()
                                        .loReqtNo(generator.generate())
                                        .productCode(result.getProductCode())
                                        .build()
                                )
                                .toList();

                        //requestProducts를 포함한 요청 DTO 재구성
                        LoanLimitAdaptorRequest adaptorRequests = adaptorRequest.toBuilder()
                                .requestProducts(requestProducts)
                                .build();

                        LoanLimitAdaptor adaptor = adaptorFactory.getAdaptor(partnerCode);
                        return CompletableFuture
                                .supplyAsync(() -> adaptor.inquireLimit(partnerCode, adaptorRequests), loanLimitExecutor)
                                .exceptionally(ex -> {
                                    //Circuit Breaker OPEN 시
                                    if(ex.getCause() instanceof CallNotPermittedException) {
                                        log.warn("[{}] Circuit Breaker OPEN - 해당 금융사 격리", partnerCode, ex);
                                        return LoanLimitAdaptorResponse.fail(partnerCode, ex.getMessage(), 0L);
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
                    }
                    else {
                        result.fail(
                                adaptorResponse.failReason(),
                                adaptorResponse.resTimeMs()
                        );
                    }

            });

            //최종 상태 결정
            long successCount = adaptorResponses.stream()
                    .filter(LoanLimitAdaptorResponse::success).count();
            InquiryStatus resultStatus = successCount == adaptorResponses.size()
                    ? InquiryStatus.SUCCESS
                    : (successCount == 0 ? InquiryStatus.PARTIAL_SUCCESS : InquiryStatus.FAILED);

            loanLimitInquiry.updateInquiryStatus(resultStatus);

            //알림 발송 - notification 도메인을 직접 알지 못함
            eventPublisher.publishEvent(
                    LoanLimitCompletedEvent.builder()
                            .userId(loanLimitInquiry.getUserId())
                            //.loReqtNo(loanLimitInquiry.getLoReqtNo())
                            .build()
            );
        } catch(Exception e) {
            log.error("한도조회 처리 중 오류. id={}", loanLimitInquiry.getId(), e);
            loanLimitInquiry.updateInquiryStatus(InquiryStatus.FAILED);
        }
    }
}
