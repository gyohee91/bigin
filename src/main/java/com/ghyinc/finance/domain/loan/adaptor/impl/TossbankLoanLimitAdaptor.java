package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.client.ApiClient;
import com.ghyinc.finance.global.client.ApiClientFactory;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import com.ghyinc.finance.global.crypto.CryptoFactory;
import com.ghyinc.finance.global.crypto.CryptoService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossbankLoanLimitAdaptor implements LoanLimitAdaptor {
    private final ApiClientFactory apiClientFactory;
    private final CryptoFactory cryptoFactory;
    private final PartnerApiProperties partnerApiProperties;

    @Builder
    private record TossbankLimitRequest(
            Data data,
            List<RequestProduct> requestProducts
    ) {}

    @Builder
    private record Data(
            String name,
            String jobType,
            String rrn,
            String corporateName,
            String automobileNumber,
            AutomobileInfo automobileInfo
    ){}

    private record AutomobileInfo(
            String seq,
            String formKind,
            String resCarNo,
            String seatingCapacity,
            String resMotorType,
            String resUseType,
            String resCarModelType
    ){}

    @Builder
    private record RequestProduct(
            String loanReqNo,
            String loanProductId
    ) {}

    private record LimitResponse(
            String resultCode
    ) {}


    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.TOSS_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest requestParam) {
        long startTime = System.currentTimeMillis();

        ApiClient apiClient = apiClientFactory.getApiClient(partnerCode);
        CryptoService cryptoService = cryptoFactory.getCryptoService(partnerCode);
        String path = partnerApiProperties.getConfig(partnerCode).getPath();

        try {
            TossbankLimitRequest request = TossbankLimitRequest.builder()
                    .data(Data.builder()
                            .rrn(cryptoService.encrypt(requestParam.rrno()))
                            .name(cryptoService.encrypt(requestParam.name()))
                            .jobType(requestParam.jobType().name())
                            .corporateName(requestParam.jobName())
                            .automobileNumber(requestParam.carNo())
                            .build()
                    )
                    .requestProducts(
                            requestParam.requestProducts().stream()
                                    .map(requestProduct -> RequestProduct.builder()
                                            .loanReqNo(requestProduct.loReqtNo())
                                            .loanProductId(requestProduct.productCode())
                                            .build()
                                    )
                                    .toList()
                    )
                    .build();

            //External API
            LimitResponse result = apiClient.post(
                    partnerCode,
                    path,
                    request,
                    LimitResponse.class
            );

            long resTimeMs = System.currentTimeMillis() - startTime;

            if(!"SUCCESS".equals(result.resultCode())) {
                log.warn("[{}] 한도조회 실패. resultCode={}", PartnerCode.TOSS_BANK, result.resultCode());
                return LoanLimitAdaptorResponse.fail(
                        PartnerCode.TOSS_BANK,
                        result.resultCode(),
                        resTimeMs
                );
            }

            log.info("[{}] 한도조회 성공, resTimeMs={}", PartnerCode.TOSS_BANK, resTimeMs);

            return LoanLimitAdaptorResponse.success(
                    PartnerCode.TOSS_BANK,
                    resTimeMs
            );
        } catch (Exception e) {
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.error("[{}] 한도조회 오류 발생", PartnerCode.TOSS_BANK, e);
            return LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, e.getMessage(), resTimeMs);
        }
    }
}
