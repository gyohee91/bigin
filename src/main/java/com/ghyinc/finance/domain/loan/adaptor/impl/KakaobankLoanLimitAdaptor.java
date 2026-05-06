package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ghyinc.finance.domain.external.nice.dto.AutoInfo;
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
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class KakaobankLoanLimitAdaptor implements LoanLimitAdaptor {
    private final ApiClientFactory apiClientFactory;
    private final CryptoFactory cryptoFactory;
    private final PartnerApiProperties partnerApiProperties;

    @Builder
    private record KakaobankLimitRequest(
            @JsonProperty("alnc_gds_infos")
            List<AlncGdsInfo> alncGdsInfos,
            @JsonProperty("rsdt_no")
            String rsdtNo,
            @JsonProperty("cust_nm")
            String custNm,
            @JsonProperty("cust_input_info")
            CustInputInfo custInputInfo,
            @JsonProperty("vhc_no")
            String vhcNo,
            @JsonProperty("car_parts")
            CarParts carParts
    ) {}

    @Builder
    private record AlncGdsInfo(
            @JsonProperty("iqry_dman_no")
            String iqryDmanNo,
            @JsonProperty("alnc_gds_unq_cd")
            String alncGdsUnqCd
    ) {}

    @Builder
    private record CustInputInfo(
            @JsonProperty("ocup_dvcd")
            String ocupDvcd,
            @JsonProperty("cur_wrst_nm")
            String curWrstNm,
            @JsonProperty("cur_wrst_encm")
            String curWrstEncm
    ) {
        public static CustInputInfo from(LoanLimitAdaptorRequest requestParam) {
            return CustInputInfo.builder()
                    .ocupDvcd(requestParam.jobType().name())
                    .curWrstNm(requestParam.jobName())
                    .curWrstEncm(requestParam.joinDate())
                    .build();
        }
    }

    @Builder
    private record CarParts(
            String seq,
            String formKind,
            String resCarNo,
            String seatingCapacity,
            String resMotorType,
            String resUseType,
            String resCarModelType
    ) {
        public static CarParts from(AutoInfo autoInfo) {
            if(Objects.isNull(autoInfo)) {
                return null;
            }

            return CarParts.builder()
                    .seq(autoInfo.seq())
                    .formKind(autoInfo.formKind())
                    .resCarNo(autoInfo.resCarNo())
                    .seatingCapacity(autoInfo.seatingCapacity())
                    .resMotorType(autoInfo.resMotorType())
                    .resUseType(autoInfo.resUseType())
                    .resCarModelType(autoInfo.resCarModelType())
                    .build();
        }
    }

    private record LimitResponse(
            String resultCode
    ) {}


    @Override
    public boolean supports(PartnerCode partnerCode) {
        return partnerCode == PartnerCode.KAKAO_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest requestParam) {
        long startTime = System.currentTimeMillis();

        CryptoService cryptoService = cryptoFactory.getCryptoService(partnerCode);
        ApiClient apiClient = apiClientFactory.getApiClient(partnerCode);
        String path = partnerApiProperties.getConfig(PartnerCode.KAKAO_BANK).getPath();

        try {
            KakaobankLimitRequest request = KakaobankLimitRequest.builder()
                    .alncGdsInfos(
                            requestParam.requestProducts().stream()
                                    .map(
                                            requestProduct -> AlncGdsInfo.builder()
                                                    .iqryDmanNo(requestProduct.loReqtNo())
                                                    .alncGdsUnqCd(requestProduct.productCode())
                                                    .build()
                                    )
                                    .toList()
                    )
                    .rsdtNo(cryptoService.encrypt(requestParam.rrno()))
                    .custNm(cryptoService.encrypt(requestParam.name()))
                    .custInputInfo(CustInputInfo.from(requestParam))
                    .vhcNo(requestParam.carNo())
                    .carParts(CarParts.from(requestParam.autoInfo()))
                    .build();

            LimitResponse result = apiClient.post(
                    partnerCode,
                    path,
                    request,
                    LimitResponse.class
            );

            long resTimeMs = System.currentTimeMillis() - startTime;

            if(!"SUCCESS".equals(result.resultCode())) {
                log.warn("[{}] 한도조회 실패. resultCode={}", PartnerCode.KAKAO_BANK, result.resultCode());
                return LoanLimitAdaptorResponse.fail(
                        PartnerCode.KAKAO_BANK,
                        result.resultCode(),
                        resTimeMs
                );
            }

            log.info("[{}] 한도조회 성공, resTimeMs={}", PartnerCode.KAKAO_BANK, resTimeMs);

            return LoanLimitAdaptorResponse.success(
                    PartnerCode.KAKAO_BANK,
                    resTimeMs
            );
        }
        catch (CallNotPermittedException e) {
            // Circuit Breaker OPEN Fallback
            // -> 해당 금융사 격리, 나머지 금융사 정상 진행 (Partial Success)
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.warn("[{}] Circuit Breaker OPEN -> Fallback 실행", PartnerCode.KAKAO_BANK);
            return LoanLimitAdaptorResponse.fail(
                    partnerCode,
                    "CB_OPEN",
                    resTimeMs
            );
        }
        catch (Exception e) {
            long resTimeMs = System.currentTimeMillis() - startTime;
            log.error("[{}] 한도조회 오류 발생", PartnerCode.KAKAO_BANK, e);
            return LoanLimitAdaptorResponse.fail(
                    PartnerCode.KAKAO_BANK,
                    e.getMessage(),
                    resTimeMs
            );
        }
    }
}
