package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.client.ApiClient;
import com.ghyinc.finance.global.client.ApiClientFactory;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import com.ghyinc.finance.global.crypto.CryptoFactory;
import com.ghyinc.finance.global.crypto.CryptoService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class LinebankLoanLimitAdaptor implements LoanLimitAdaptor {
    private final ApiClientFactory apiClientFactory;
    private final CryptoFactory cryptoFactory;
    private final PartnerApiProperties partnerApiProperties;

    @Builder
    private record LinebankLimitRequest(
            PreScreeningRequest preScreeningRequest
    ){}

    @Builder
    private record PreScreeningRequest(
            Data data,
            List<RequestProduct> requestProducts
    ){}

    @Builder
    private record Data(
            boolean agreePersonalCreditInfo,
            boolean agreeIdentifyInfo,
            String name,
            String rrn,
            String ci,
            String authSmsTime,
            String jobType,
            String joinDate,
            String carNo
    ){}

    @Builder
    private record RequestProduct(
            String ticketId,
            String loanProductId
    ) {}

    private record LinebankLimitResponse(
            String resultCode
    ) {}

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.LINE_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest requestParam) {
        long startTime = System.currentTimeMillis();

        ApiClient apiClient = apiClientFactory.getApiClient(partnerCode);
        CryptoService cryptoService = cryptoFactory.getCryptoService(partnerCode);
        String path = partnerApiProperties.getConfig(partnerCode).getPath();

        try {
            PreScreeningRequest preScreeningRequest = PreScreeningRequest.builder()
                    .data(Data.builder()
                            .name(cryptoService.encrypt(requestParam.name()))
                            .rrn(cryptoService.encrypt(requestParam.rrno()))
                            .ci(null)
                            .jobType(requestParam.jobType().name())
                            .joinDate(requestParam.joinDate())
                            .carNo(requestParam.carNo())
                            .build()
                    )
                    .requestProducts(
                            requestParam.requestProducts().stream()
                                    .map(requestProduct -> RequestProduct.builder()
                                            .ticketId(requestProduct.loReqtNo())
                                            .loanProductId(requestProduct.productCode())
                                            .build()
                                    )
                                    .toList()
                    )
                    .build();

            LinebankLimitRequest request = LinebankLimitRequest.builder()
                    .preScreeningRequest(preScreeningRequest)
                    .build();

            // External API
            LinebankLimitResponse result = apiClient.post(
                    partnerCode,
                    path,
                    request,
                    LinebankLimitResponse.class
            );

            long resTimeMs = System.currentTimeMillis() - startTime;

            if (!"SUCCESS".equals(result.resultCode())) {
                log.warn("[{}] 한도조회 실패. resultCode={}", PartnerCode.LINE_BANK, result.resultCode());
                return LoanLimitAdaptorResponse.fail(
                        PartnerCode.LINE_BANK,
                        result.resultCode(),
                        resTimeMs
                );
            }

            log.info("[{}] 한도조회 성공, resTimeMs={}", PartnerCode.LINE_BANK, resTimeMs);

            return LoanLimitAdaptorResponse.success(
                    PartnerCode.LINE_BANK,
                    resTimeMs
            );

        }
        catch (CallNotPermittedException e) {
            // Circuit Breaker OPEN Fallback
            // -> 해당 금융사 격리, 나머지 금융사 정상 진행 (Partial Success)
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.warn("[{}] Circuit Breaker OPEN -> Fallback 실행", PartnerCode.LINE_BANK);
            return LoanLimitAdaptorResponse.fail(
                    partnerCode,
                    "CB_OPEN",
                    resTimeMs
            );
        }
        catch (Exception e) {
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.error("[{}] 한도조회 오류 발생", PartnerCode.LINE_BANK, e);
            return LoanLimitAdaptorResponse.fail(
                    PartnerCode.LINE_BANK,
                    e.getMessage(),
                    resTimeMs
            );
        }
    }
}
