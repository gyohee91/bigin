package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 한도결과 콜백 수신 서비스
 *
 * <p>금융사로부터 한도조회 결과 콜백을 수신하여 선저장된 {@code LoanLimitProductResult}를
 * 갱신하고 {@code LoanLimitInquiry}의 콜백 수신 카운트를 증가시킨다.</p>
 *
 * <h3>콜백 처리 구조</h3>
 * <ul>
 *     <li>금융사별 응답 포맷이 상이하므로 {@link LoanLimitResultAdaptor}를 통해
 *          공통 요청 DTO{@code LoanLimitResultRequest}로 변환한다</li>
 *     <li>비관락{@code PESSIMISTIC_WRITE}으로 동일 Inquiry에 대한 콜백이
 *          동시에 수신될 때 순차 처리를 보장한다</li>
 * </ul>
 *
 * <h3>콜백 처리 예외 전략</h3>
 * <p>금융사는 응답 코드 (성공/실패 여부)를 기대한다. 처리 중 예외가 발생해도
 * 금융사에 오류 응답을 반환하여 금융사 측 재전송 여부를 제어한다.
 * 예외를 상위로 전파하면 금융사가 응답을 받지 못해 재전송이 반복될 수 있다.</p>
 *
 * @see LoanLimitResultAdaptor
 * @see LoanLimitResultAdaptorFactory
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitResultService {
    private final LoanLimitResultAdaptorFactory resultAdaptorFactory;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;

    /**
     * 금융사 한도조회 콜백을 수신하여 처리한다
     *
     * <p>요청 본문{@code reqBody}은 금융사별 포맷이므로 {@link LoanLimitResultAdaptor}가
     * 공통 DTO로 변환한다. 변환 후 각 상품 결과를 순차 처리하며 Inquiry 상태를 갱신한다.</p>
     *
     * <h3>처리 순서</h3>
     * <ol>
     *     <li>partnerCode 유효성 검증 및 금융사별 Adaptor 조회</li>
     *     <li>금융사별 요청 포맷을 공통 DTO {@link LoanLimitResultRequest}로 변환</li>
     *     <li>loReqtNo + productCode로 선저장된 ProductResult 조회</li>
     *     <li>비관락으로 Inquiry 조회 (동시 콜백 순차 처리)</li>
     *     <li>중복 수신 및 처리 불가 상태 체크</li>
     *     <li>한도금액, 금리 UPDATE + 콜백 카운트 증가</li>
     * </ol>
     *
     * <h3>처리 불가 상태 분류</h3>
     * <ul>
     *     <li>{@code SUCCESS}: 이미 처리 완료된 콜백 (중복 수신) → 경고 로그 후 skip</li>
     *     <li>{@code SEND_FAILED / TIMEOUT}: 전송 실패 또는 타임아웃 처리된 건 → skip</li>
     *     <li>{@code SEND_SUCCESS}: 정상 처리 대상</li>
     * </ul>
     *
     * @param requestPartnerCode    금융사 코드 (Path Variable)
     * @param reqBody               금융사별 요청 본문 (금융사 포맷 그대로 수신)
     * @return      금융사에 반환할 처리 결과 응답 DTO
     */
    @Transactional
    public ResultResponse responseCompareLoanResult(String requestPartnerCode, JsonNode reqBody) {
        PartnerCode partnerCode = Optional.of(PartnerCode.valueOf(requestPartnerCode))
                .orElseThrow(() -> new InvalidRequestException("유효하지 않은 partnerCode. PartnerCode: " + requestPartnerCode));

        LoanLimitResultAdaptor adaptor = resultAdaptorFactory.getAdaptor(partnerCode);

        try {
            // 금융사별 응답 포맷에 맞는 Adaptor를 선택한다
            LoanLimitResultRequest request = adaptor.convert(reqBody);

            request.getLoanApplyResults().forEach(item -> {
                // loReqtNo와 productCode로 선저장된 ProductResult 조회
                var productResult = loanLimitProductResultRepository.findByLoReqtNoAndProductCode(item.getLoReqtNo(), item.getProductCode())
                        .orElseThrow(() -> new InvalidRequestException("존재하지 않는 식별번호&상품코드. loReqtNo: " + item.getLoReqtNo() + ", productCode: " + item.getProductCode()));

                // 비관적 Lock으로 동시 수신 시 순차 처리 보장
                var loanLimitInquiry = loanLimitProductResultRepository.findInquiryByLoReqtNoAndProduceCodeWithLock(item.getLoReqtNo(), item.getProductCode())
                        .orElseThrow(() -> new InvalidRequestException("존재하지 않는 한도조회 이력"));

                // SEND_SUCCESS 상태가 아닌 경우 처리 불가 상태로 간주하고 skip한다.
                // 중복 수신(SUCCESS) 또는 전송 실패/타임아웃된 건은 결과를 덮어쓰지 않는다
                if(productResult.getStatus() != PartnerInquiryStatus.SEND_SUCCESS) {
                    log.warn("[{}] 처리 불가 상태의 결과 수신. loReqtNo={}, status={}",
                            partnerCode, item.getLoReqtNo(), productResult.getStatus());

                    if(productResult.getStatus() == PartnerInquiryStatus.SUCCESS) {
                        log.warn("[{}] 중복 수신. loReqtNo={}",
                                partnerCode, item.getLoReqtNo());
                    }

                    return;
                }

                // 콜백 수신 카운트 증가 및 한도결과 UPDATE
                // incrementSuccessCount(): Inquiry의 successProductCount 증가
                loanLimitInquiry.incrementSuccessCount();
                productResult.updateResult(item.getResultCode(), item.getAmount(), item.getInterestRate());
            });

            return adaptor.buildResponse(true, "한도결과 API 정상 처리");
        }
        catch (InvalidRequestException e) {
            // 유효성 오류: 금융사에 실패 응답 반환 (재전송 방지)
            log.error("[{}] 한도결과 API 처리 중 오류. message={}", requestPartnerCode, e.getMessage());
            return adaptor.buildResponse(false, e.getMessage());
        }
        catch (Exception e) {
            log.error("[{}] 한도결과 API 처리 중 오류. ", requestPartnerCode, e);
            return adaptor.buildResponse(false, "처리 중 오류가 발생했습니다");
        }
    }

}
