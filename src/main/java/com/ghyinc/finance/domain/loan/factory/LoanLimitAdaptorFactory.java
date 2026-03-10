package com.ghyinc.finance.domain.loan.factory;

import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 금융사 코드에 따른 Adaptor 반환 팩토리
 * <p>
 * Strategy와 동일하게 Spring DI로 Adaptor 구현체를 자동 수집
 * 새로운 금융사 연동 시 Adaptor 구현체만 추가하면 됨.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoanLimitAdaptorFactory {
    private final List<LoanLimitAdaptor> adaptors;

    public LoanLimitAdaptor getAdaptor(PartnerCode partnerCode) {
        return adaptors.stream()
                .filter(adaptor -> adaptor.supports(partnerCode))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("지원하지 않는 금융사 입니다. " + partnerCode));
    }

    public List<LoanLimitAdaptor> getAdaptors(List<PartnerCode> partnerCodes) {
        return partnerCodes.stream()
                .map(this::getAdaptor)
                .collect(Collectors.toList());
    }
}
