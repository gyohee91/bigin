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

@Component
@RequiredArgsConstructor
public class LoanLimitDispatchPlanner {
    private final LoanLimitStrategyFactory strategyFactory;

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
