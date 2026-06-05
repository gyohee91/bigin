package com.ghyinc.finance.domain.loan.adaptor.callback;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 금융사 코드에 따른 {@link LoanLimitResultAdaptor} 구현체를 반환하는 팩토리.
 *
 * <p>Spring DI를 통해 {@code LoanLimitResultAdaptor} 구현체 전체를 자동으로 수집한다.
 * {@code supports()} 메서드를 순차 평가하여 콜백 요청을 처리할 수 있는 어댑터를 선택한다.</p>
 *
 * <h3>어댑터 선택 방식</h3>
 * <pre>
 *     getAdaptor(KAKAO_BANK)
 *      → CommonLoanLimitResultAdaptor.supports(KAKAO_BANK)     → false (비표준)
 *      → KakaobankLoanLimitResultAdaptor.supports(KAKAO_BANK)  → true  ← 선택
 *
 *     getAdaptor(KB_BANK)
 *      → CommonLoanLimitResultAdaptor.supports(KB_BANK)        → true  ← 선택 (표준)
 * </pre>
 *
 * <h3>신규 금융사 추가 방법</h3>
 * <ol>
 *     <li>{@link LoanLimitResultAdaptor}를 구현하는 클래스 작성</li>
 *     <li>{@code supports()}에서 해당 {@code PartnerCode} 반환하도록 구성</li>
 *     <li>{@code @Component} 선언으로 Spring Bean에 등록</li>
 *     <li>Factory 수정 불필요 - 자동으로 탐색 대상에 포함됨</li>
 * </ol>
 *
 * @see LoanLimitResultAdaptor
 * @see com.ghyinc.finance.domain.loan.service.LoanLimitResultService
 */
@Component
@RequiredArgsConstructor
public class LoanLimitResultAdaptorFactory {

    /**
     * Spring이 수집한 전체 콜백 어댑터 구현체 목록.
     * {@code supports()} 순차 평가로 적합한 어댑터를 탐색한다.
     */
    private final List<LoanLimitResultAdaptor> adaptors;

    /**
     * 금융사 코드에 해당하는 {@link LoanLimitResultAdaptor}를 반환한다.
     *
     * <p>등록된 어댑터를 순차 탐색하여 {@code supports(partnerCode)}가
     * {@code true}인 첫 번째 구현체를 반환한다. 표준 포맷 금융사는
     * {@code CommonLoanLimitResultAdaptor}가, 비표준 금융사는 전용 구현체가 처리한다.</p>
     *
     * <p>어댑터 목록의 탐색 순서가 결과에 영향을 줄 수 있다.
     * 전용 어댑터가 표준 어댑터보다 먼저 탐색되도록
     * {@code @Order} 또는 Bean 등록 순서를 고려해야 한다.</p>
     *
     * @param partnerCode   금융사 코드
     * @return  해당 금융사 콜백을 처리하는 어댑터 구현체
     * @throws  InvalidRequestException 지원하지 않는 금융사 코드인 경우
     */
    public LoanLimitResultAdaptor getAdaptor(PartnerCode partnerCode) {
        return adaptors.stream()
                .filter(adaptor -> adaptor.supports(partnerCode))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("콜백 Adaptor 없음: " + partnerCode));
    }
}
