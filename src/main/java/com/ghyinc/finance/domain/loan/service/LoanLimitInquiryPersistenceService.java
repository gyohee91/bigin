package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.PreparedFanout;
import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.global.event.PartnerTransmissionAuditEvent;
import com.ghyinc.finance.global.outbox.service.OutboxEventWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link LoanLimitSenderService}의 DB 접근 전용 협력자.
 *
 * <p>과거엔 {@code inquiry()} 메서드 하나가 선저장 → 파트너 팬아웃 대기(join) → 결과반영까지
 * 전부 단일 {@code @Transactional}로 묶여 있어, Hikari 커넥션 1개를 파트너 응답을 기다리는
 * 동안(최대 partnerOrTimeout, 수 초) 계속 붙잡고 있었다. 부하가 걸려 파트너 응답이 느려지면
 * 커넥션 점유시간이 같이 늘어나고, 그만큼 더 많은 동시 커넥션이 필요해지는 피드백 루프가 생겨
 * Hikari 풀이 고갈되는 근본 원인이었다.</p>
 *
 * <p>이 클래스는 DB 트랜잭션이 필요한 두 구간(선저장/결과반영)만 짧게 분리해서 제공한다.
 * 파트너 팬아웃 대기(join)는 {@link LoanLimitSenderService#inquiry}에서 트랜잭션 없이 수행되므로
 * 그 구간 동안은 Hikari 커넥션을 전혀 점유하지 않는다.</p>
 *
 * <p>주의(Spring self-invocation): 이 메서드들은 반드시 {@link LoanLimitSenderService}가
 * 별도 빈으로 주입받아 프록시를 통해 호출해야 {@code @Transactional}이 적용된다. 같은 클래스
 * 안에서 내부 메서드 호출로 합치면 AOP 프록시를 거치지 않아 트랜잭션이 시작되지 않는다.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitInquiryPersistenceService {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final ProductService productService;
    private final OutboxEventWriter outboxEventWriter;
    private final LoReqtNoGenerator generator;

    /**
     * 1단계: 선저장 트랜잭션 (짧게, commit).
     *
     * <p>Result/ProductResult를 금융사·상품당 1건씩 INSERT하고 Inquiry를 IN_PROGRESS로 전이한 뒤
     * 즉시 커밋한다. 파트너 응답을 기다리지 않으므로 Hikari 커넥션 점유시간은 수 ms 수준이다.</p>
     */
    @Transactional
    public PreparedFanout preSave(
            long id,
            List<PartnerCode> partnerCodes,
            LoanLimitAdaptorRequest adaptorRequest
    ) {
        LoanLimitInquiry loanLimitInquiry = loanLimitInquiryRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 조회 이력: " + id));

        loanLimitInquiry.updateInquiryStatus(InquiryStatus.IN_PROGRESS);

        // 각 금융사에 대한 Result 선저장
        partnerCodes.forEach(partnerCode -> {
            LoanLimitResult result = LoanLimitResult.builder()
                    .loanLimitInquiry(loanLimitInquiry)
                    .partnerCode(partnerCode)
                    .build();
            loanLimitInquiry.addResult(result);
        });

        // 금융사별 상품 조회 및 ProductResult 선저장 + 팬아웃용 RequestProduct DTO 구성
        Map<PartnerCode, List<RequestProduct>> requestProductMap = partnerCodes.stream()
                .collect(Collectors.toMap(
                        partnerCode -> partnerCode,
                        partnerCode -> productService.getActiveProducts(partnerCode, adaptorRequest.loanType())
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
                                    return RequestProduct.builder()
                                            .loReqtNo(productResult.getLoReqtNo())
                                            .productCode(productResult.getProductCode())
                                            .build();
                                }).toList()
                ));

        // 상품 전체 수 초기화
        int totalProductCount = requestProductMap.values().stream()
                .mapToInt(List::size)
                .sum();
        loanLimitInquiry.initProductCount(totalProductCount);

        return new PreparedFanout(loanLimitInquiry.getId(), requestProductMap);
    }

    /**
     * 2단계: 결과반영 트랜잭션 (짧게, 새 트랜잭션).
     *
     * <p>파트너 팬아웃 완료 후 응답을 집계해 Result/ProductResult 상태를 UPDATE하고, Inquiry
     * 최종 상태를 결정한 뒤 Outbox 이벤트를 같은 트랜잭션에 INSERT한다(outbox 패턴 - 비즈니스
     * 엔티티 변경과 이벤트 저장이 항상 같은 트랜잭션에 있어야 한다).</p>
     */
    @Transactional
    public void applyResults(long id, List<LoanLimitAdaptorResponse> adaptorResponses) {
        LoanLimitInquiry loanLimitInquiry = loanLimitInquiryRepository.findById(id)
                .orElseThrow(() -> new InvalidRequestException("존재하지 않는 조회 이력: " + id));

        Map<PartnerCode, LoanLimitResult> resultMap = loanLimitInquiry.getResults().stream()
                .collect(Collectors.toMap(LoanLimitResult::getPartnerCode, result -> result));
        Map<PartnerCode, List<LoanLimitProductResult>> productResultMap = loanLimitInquiry.getProductResults().stream()
                .collect(Collectors.groupingBy(LoanLimitProductResult::getPartnerCode));

        // 전송 결과에 따라 Result / ProductResult 상태 UPDATE
        // 성공: SEND_SUCCESS, 실패: SEND_FAILED (콜백 대기 여부 결정)
        adaptorResponses.forEach(adaptorResponse -> {
            LoanLimitResult result = resultMap.get(adaptorResponse.partnerCode());

            if (adaptorResponse.success()) {
                result.success(adaptorResponse.resTimeMs());
                productResultMap.get(adaptorResponse.partnerCode())
                        .forEach(LoanLimitProductResult::sendSuccess);
            } else {
                result.fail(
                        adaptorResponse.failReason(),
                        adaptorResponse.resTimeMs()
                );

                productResultMap.get(adaptorResponse.partnerCode())
                        .forEach(LoanLimitProductResult::sendFail);
            }

            // 파트너 전송 이력 감사 로그
            outboxEventWriter.enqueue(
                    "PartnerTransmission",
                    loanLimitInquiry.getInquiryNo(),
                    "PARTNER_TRANSMISSION",
                    PartnerTransmissionAuditEvent.from(loanLimitInquiry.getInquiryNo(), adaptorResponse)
            );
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
        if (!Objects.equals(InquiryStatus.FAILED, resultStatus)) {
            // Spring 이벤트 발행 (트랜잭션 커밋 후 Kafka 발행 트리거)
            outboxEventWriter.enqueue(
                    "LoanLimitInquiry",
                    loanLimitInquiry.getInquiryNo(),
                    "LOAN_LIMIT_COMPLETED",
                    LoanLimitCompletedEvent.from(loanLimitInquiry)
            );
        }
    }

    /**
     * 선저장/팬아웃/결과반영 중 예상치 못한 예외가 발생했을 때 best-effort로 FAILED 처리한다.
     * 별도의 짧은 트랜잭션으로 수행하므로, preSave()나 applyResults()의 롤백과 무관하게 동작한다.
     */
    @Transactional
    public void markFailed(long id) {
        loanLimitInquiryRepository.findById(id).ifPresentOrElse(
                loanLimitInquiry -> {
                    loanLimitInquiry.updateInquiryStatus(InquiryStatus.FAILED);
                    log.error("한도조회 처리 중 오류로 FAILED 처리. id={}", id);
                },
                () -> log.error("FAILED 처리 대상 조회 이력 없음. id={}", id)
        );
    }
}
