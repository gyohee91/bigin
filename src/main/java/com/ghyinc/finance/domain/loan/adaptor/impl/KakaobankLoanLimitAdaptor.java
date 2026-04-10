package com.ghyinc.finance.domain.loan.adaptor.impl;

import com.fasterxml.jackson.annotation.JsonProperty;
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
            String curWrstNm
    ) {}

    @Builder
    private record CarParts(
            String seq,
            String formKind,
            String resCarNo,
            String seatingCapacity,
            String resMotorType,
            String resUseType,
            String resCarModelType
    ) {}

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
                    .custInputInfo(
                            CustInputInfo.builder()
                                    .ocupDvcd(requestParam.jobType().name())
                                    .curWrstNm(requestParam.jobName())
                                    .build()
                    )
                    .vhcNo(requestParam.carNo())
                    .carParts(
                            CarParts.builder()
                                    .seq(requestParam.autoInfo().seq())
                                    .formKind(requestParam.autoInfo().formKind())
                                    .resCarNo(requestParam.autoInfo().resCarNo())
                                    .seatingCapacity(requestParam.autoInfo().seatingCapacity())
                                    .resMotorType(requestParam.autoInfo().resMotorType())
                                    .resUseType(requestParam.autoInfo().resUseType())
                                    .resCarModelType(requestParam.autoInfo().resCarModelType())
                                    .build()
                    )
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
        } catch (Exception e) {
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
