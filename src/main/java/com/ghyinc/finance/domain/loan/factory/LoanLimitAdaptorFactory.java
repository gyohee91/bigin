package com.ghyinc.finance.domain.loan.factory;

import com.ghyinc.finance.domain.loan.adaptor.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
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
public class LoanLimitAdaptorFactory {
    private final Map<PartnerCode, LoanLimitAdaptor> adaptorMap;

    public LoanLimitAdaptorFactory(List<LoanLimitAdaptor> adaptors) {
        this.adaptorMap = adaptors.stream()
                .collect(Collectors.toMap(LoanLimitAdaptor::getPartnerCode, Function.identity()));
    }

    public LoanLimitAdaptor getAdaptor(PartnerCode partnerCode) {
        LoanLimitAdaptor adaptor = adaptorMap.get(partnerCode);

        if(Objects.isNull(adaptor)) {
            log.error("지원하지 않는 금융사 코드. PartnerCode: {}", partnerCode);
            throw new InvalidRequestException("지원하지 않는 금융사입니다. " + partnerCode);
        }

        return adaptor;
    }

    public List<LoanLimitAdaptor> getAdaptors(List<PartnerCode> partnerCodes) {
        return partnerCodes.stream()
                .map(this::getAdaptor)
                .collect(Collectors.toList());
    }
}
