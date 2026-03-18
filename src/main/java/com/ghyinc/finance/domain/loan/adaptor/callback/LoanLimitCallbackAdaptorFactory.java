package com.ghyinc.finance.domain.loan.adaptor.callback;

import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoanLimitCallbackAdaptorFactory {
    private final List<LoanLimitResultAdaptor> adaptors;

    public LoanLimitResultAdaptor getAdaptor(PartnerCode partnerCode) {
        return adaptors.stream()
                .filter(adaptor -> adaptor.supports(partnerCode))
                .findFirst()
                .orElseThrow(() -> new InvalidRequestException("콜백 Adaptor 없음: " + partnerCode));
    }
}
