package com.ghyinc.finance.domain.loan.adaptor.common;

import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommonLoanLimitAdaptor implements LoanLimitAdaptor {
    private final PartnerApiProperties partnerApiProperties;
    private final Map<PartnerCode, RestClient> partnerRestClients;

    /*
    public CommonLoanLimitAdaptor(
            Map<PartnerCode, RestClient> partnerRestClients,
            PartnerApiProperties partnerApiProperties
    ) {
        //PartnerCode 키로 전용 RestClient 주입
        this.restClient = partnerRestClients.get(PartnerCode.KAKAO_BANK);
        this.path = partnerApiProperties.getConfig(PartnerCode.KAKAO_BANK).getPath();
    }

     */

    @Builder
    private record CommonLimitRequest(
            String loReqtNo,
            String productCd,
            String rrn,
            String name,
            String jobType
    ) {}

    private record CommonLimitResponse(
            String resultCode
    ) {}

    private static final Set<PartnerCode> SUPPORTED_PARTNERS = Set.of(
            PartnerCode.K_BANK,
            PartnerCode.SHINHAN_BANK,
            PartnerCode.LINE_BANK
    );

    @Override
    public boolean supports(PartnerCode partnerCode) {
        return SUPPORTED_PARTNERS.contains(partnerCode);
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(PartnerCode partnerCode, LoanLimitAdaptorRequest requestParam) {
        long startTime = System.currentTimeMillis();

        RestClient restClient = partnerRestClients.get(partnerCode);
        String path = partnerApiProperties.getConfig(partnerCode).getPath();

        try {
            CommonLimitRequest request = CommonLimitRequest.builder()
                    .loReqtNo(requestParam.loReqtNo())
                    .rrn(requestParam.rrno())
                    .name(requestParam.name())
                    .build();

            CommonLimitResponse result = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(CommonLimitResponse.class);

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
