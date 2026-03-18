package com.ghyinc.finance.domain.loan.adaptor.common;

import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.RequestProduct;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.client.ApiClient;
import com.ghyinc.finance.global.client.ApiClientFactory;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommonLoanLimitAdaptor implements LoanLimitAdaptor {
    private final ApiClientFactory apiClientFactory;
    private final PartnerApiProperties partnerApiProperties;

    @Builder
    private record CommonLimitRequest(
            List<RequestProduct> requestProducts,
            String rrn,
            String name,
            String jobType
    ) {}

    private record CommonLimitResponse(
            String resultCode
    ) {}

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode.isStandard();
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest requestParam) {
        long startTime = System.currentTimeMillis();

        //통신 방식에 맞는 ApiClient 자동 선택
        ApiClient apiClient = apiClientFactory.getApiClient(partnerCode);
        String path = partnerApiProperties.getConfig(partnerCode).getPath();

        try {
            CommonLimitRequest request = CommonLimitRequest.builder()
                    .requestProducts(requestParam.requestProducts())
                    .rrn(requestParam.rrno())
                    .name(requestParam.name())
                    .build();

            CommonLimitResponse result = apiClient.post(
                    partnerCode,
                    path,
                    request,
                    CommonLimitResponse.class
            );

            long resTimeMs = System.currentTimeMillis() - startTime;

            if(!"SUCCESS".equals(result.resultCode())) {
                log.warn("[{}] 한도조회 실패. resultCode={}", partnerCode, result.resultCode());
                return LoanLimitAdaptorResponse.fail(
                        partnerCode,
                        result.resultCode(),
                        resTimeMs
                );
            }

            return LoanLimitAdaptorResponse.success(
                    partnerCode,
                    resTimeMs
            );
        }
        catch (Exception e) {
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.error("[{}] 한도조회 오류 발생", partnerCode, e);
            return LoanLimitAdaptorResponse.fail(
                    partnerCode,
                    e.getMessage(),
                    resTimeMs
            );
        }

    }


}
