package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.ExternalDataError;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.external.coocon.dto.KbAppraisalResult;
import com.ghyinc.finance.domain.external.coocon.service.KbAppraisalService;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class MorgageLoanLimitStrategy implements LoanLimitStrategy {
    private final KbAppraisalService kbAppraisalService;
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;

    @Override
    public LoanType getLoanType() {
        return LoanType.MORTGATE;
    }

    @Override
    public List<PartnerCode> getSupportedBanks() {
        return partnerLoanTypeRepository.findActivePartnerCodeByLoanType(this.getLoanType());
    }

    @Override
    public void validate(LoanLimitRequest request) {
        // 법정동코드 필수 검증
        if(Objects.isNull(request.getKbIdentityCode()) || request.getKbIdentityCode().isBlank()) {
            throw new InvalidRequestException("주택담보대출은 법정동코드가 필수입니다");
        }
    }

    @Override
    public ExternalDataContext fetchExternalData(LoanLimitRequest request) {
        try {
            KbAppraisalResult result = kbAppraisalService.inquireKbAppraisal(request.getAddress());
            return ExternalDataContext.builder()
                    .kbAppraisalResult(result)
                    .build();
        } catch (ExternalApiFailException e) {
            log.error("KB 부동산 조회 실패. address={}", request.getAddress(), e);

            // 예외를 던지지 않고 오류 정보만 context에 담아 return
            return ExternalDataContext.builder()
                    .errors(Map.of("KB_APPRAISAL",
                            ExternalDataError.builder()
                                    .code("KB_APPRAISAL_ERROR")
                                    .message(e.getMessage())
                                    .build()
                    ))
                    .build();
        }
    }

    @Override
    public LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext) {
        KbAppraisalResult result = externalDataContext.kbAppraisalResult();
        return LoanLimitAdaptorRequest.builder()
                .name(request.getName())
                .rrno(request.getRrno())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .address(request.getAddress())
                .respData(result.respData())
                .build();
    }

    @Override
    public boolean requiresExternalData() {
        return true;
    }

    @Override
    public List<PartnerCode> filterAvailablePartners(List<PartnerCode> activePartnerCodes, ExternalDataContext context) {
        if(!context.hasKbAppraisalError()) {
            return activePartnerCodes;  // KB 시세 정상 -> 전체 금융사 진행
        }

        log.warn("KB부동산 시세 조회 실패. KB시세 불필요 금융사만 진행");

        // KB부동산 데이터가 없어도 자체 심사 가능한 금융사만 필터링
        return activePartnerCodes.stream()
                .filter(partnerCode -> !this.requiresKbAppraisal(partnerCode))
                .toList();
    }

    private boolean requiresKbAppraisal(PartnerCode partnerCode) {
        // KB시세가 필수인 금융사 목록
        return Set.of(
                PartnerCode.KB_CAPITAL,
                PartnerCode.SHINHAN_BANK
        ).contains(partnerCode);
    }
}
