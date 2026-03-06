package com.ghyinc.finance.domain.loan.adaptor;

import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.config.PartnerApiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class KakaobankLoanLimitAdaptor implements LoanLimitAdaptor {
    private final RestClient restClient;
    private final String path;

    public KakaobankLoanLimitAdaptor(
            Map<PartnerCode, RestClient> partnerRestClients,
            PartnerApiProperties partnerApiProperties
    ) {
        //PartnerCode 키로 전용 RestClient 주입
        this.restClient = partnerRestClients.get(PartnerCode.KAKAO_BANK);
        this.path = partnerApiProperties.getConfig(PartnerCode.KAKAO_BANK).getPath();
    }

    @Value("${notification.sender.base-url}")
    private String url;

    private record LimitResponse(
            String resultCode
    ) {}

    @Override
    public PartnerCode getPartnerCode() {
        return PartnerCode.KAKAO_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(LoanLimitAdaptorRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            LimitResponse result = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(LimitResponse.class);

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
