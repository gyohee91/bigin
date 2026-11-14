package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.ExternalDataError;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.PartnerDispatchPlan;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 한도조회 요청을 받아 "어떤 금융사에 무엇을 보낼지"를 계산하는 계획 수립기.
 *
 * <p>대출 유형별 {@link LoanLimitStrategy}에 위임하여 검증 → 외부 데이터 조회 → 금융사 선정 →
 * 전송용 요청 변환까지 수행하고 {@link PartnerDispatchPlan}으로 돌려준다.
 * DB 를 쓰지 않으므로 트랜잭션이 필요 없고, Redis 락 경로와 DB 선점 경로가 공유한다.</p>
 */
@Component
@RequiredArgsConstructor
public class LoanLimitDispatchPlanner {
    private final LoanLimitStrategyFactory strategyFactory;

    /**
     * 요청을 검증하고 전송 계획(대상 금융사 + 전송용 요청)을 만든다.
     *
     * <p>금융사 선정은 이중 구조다: {@code getSupportedBanks()}(코드 레벨, 대출 유형별 고정)로 후보를 정하고,
     * {@code filterAvailablePartners()}로 외부 데이터 조회 실패 시 해당 금융사를 동적으로 제외한다.</p>
     *
     * @throws InvalidRequestException 입력값 검증 실패, 지원 금융사 없음, 필터링 후 조회 가능 금융사 없음
     */
    public PartnerDispatchPlan plan(LoanLimitRequest request) {
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

        // 어댑터 요청 DTO 변환 (Strategy)
        // 대출 유형별 전략으로 금융사 전송용 요청 DTO 생성
        LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request, context);

        return new PartnerDispatchPlan(availablePartnerCodes, adaptorRequest);
    }
}
