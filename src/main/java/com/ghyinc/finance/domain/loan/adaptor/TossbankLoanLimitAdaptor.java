package com.ghyinc.finance.domain.loan.adaptor;

import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossbankLoanLimitAdaptor implements LoanLimitAdaptor {
    private final RestTemplate restTemplate;

    @Value("${notification.sender.base-url}")
    private String url;

    private record LimitResponse(
            String resultCode
    ) {}

    @Override
    public PartnerCode getPartnerCode() {
        return PartnerCode.TOSS_BANK;
    }

    @Override
    public LoanLimitAdaptorResponse inquireLimit(LoanLimitAdaptorRequest request) {
        long startTime = System.currentTimeMillis();

        try {
            long resTimeMs = System.currentTimeMillis() - startTime;

            //External API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<LoanLimitAdaptorRequest> httpEntity = new HttpEntity<>(request, headers);

            ResponseEntity<LimitResponse> responseEntity = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    httpEntity,
                    LimitResponse.class
            );

            LimitResponse result = responseEntity.getBody();

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
