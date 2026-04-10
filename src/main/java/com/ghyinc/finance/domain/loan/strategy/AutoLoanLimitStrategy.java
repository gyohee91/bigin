package com.ghyinc.finance.domain.loan.strategy;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.ExternalDataError;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.external.nice.dto.NiceDnrResult;
import com.ghyinc.finance.domain.external.nice.service.NiceDnrService;
import com.ghyinc.finance.domain.loan.repository.PartnerLoanTypeRepository;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class AutoLoanLimitStrategy implements LoanLimitStrategy{
    private final NiceDnrService niceDnrService;
    private final PartnerLoanTypeRepository partnerLoanTypeRepository;

    @Override
    public LoanType getLoanType() {
        return LoanType.AUTO;
    }

    @Override
    public List<PartnerCode> getSupportedBanks() {
        return partnerLoanTypeRepository.findActivePartnerCodeByLoanType(this.getLoanType());
    }

    @Override
    public void validate(LoanLimitRequest request) {
        // 차량번호 필수 검증
        if(Objects.isNull(request.getCarNo()) || request.getCarNo().isBlank()) {
            throw new InvalidRequestException("오토담보 대출은 차량번호가 필수입니다");
        }
    }

    @Override
    public ExternalDataContext fetchExternalData(LoanLimitRequest request) {
        try {
            NiceDnrResult result = niceDnrService.inquireNiceDnr(request.getCarNo(), request.getName());
            return ExternalDataContext.builder()
                    .niceDnrResult(result)
                    .build();
        } catch (ExternalApiFailException e) {
            log.error("Nice DNR 조회 실패. carNo={}", request.getCarNo(), e);

            // 예외를 던지지 않고 오류 정보만 context에 담아 return
            return ExternalDataContext.builder()
                    .errors(Map.of("NICE_DNR",
                            ExternalDataError.builder()
                                    .code("NICE_DNR_ERROR")
                                    .message(e.getMessage())
                                    .build()
                    ))
                    .build();
        }
    }

    @Override
    public LoanLimitAdaptorRequest toAdaptorRequest(LoanLimitRequest request, ExternalDataContext externalDataContext) {
        NiceDnrResult result = externalDataContext.niceDnrResult();
        return LoanLimitAdaptorRequest.builder()
                .name(request.getName())
                .rrno(request.getRrno())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .loanType(request.getLoanType())
                .carNo(request.getCarNo())
                .autoInfo(result.autoInfo())
                .autoSecondInfo(result.autoSecondInfo())
                .build();
    }

    @Override
    public boolean requiresExternalData() {
        return true;
    }

    @Override
    public List<PartnerCode> filterAvailablePartners(List<PartnerCode> activePartnerCodes, ExternalDataContext context) {
        // Nice DNR 실패 시 차량정보가 필요한 금융사 제외
        if(!context.hasNiceDnrError()) {
            log.warn("Nice DNR 조회 실패로 오토담보 한도조회 불가");
            return List.of();
        }

        return activePartnerCodes;
    }
}
