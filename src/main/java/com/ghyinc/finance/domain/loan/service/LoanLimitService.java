package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitService {
    private final LoanLimitSenderService loanLimitSenderService;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final LoanLimitStrategyFactory strategyFactory;
    private final LoanLimitAdaptorFactory adaptorFactory;

    @Transactional
    public LoanLimitResponse requestCompareLoan(LoanLimitRequest request) {
        LoanLimitStrategy strategy = strategyFactory.getStrategy(request.getLoanType());

        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .loanType(request.getLoanType())
                .build();

        loanLimitInquiryRepository.save(inquiry);

        // 어댑터 요청 DTO 변환 (Strategy)
        LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request);

        // 지원 은행 목록 조회 (Strategy) -> 어댑터 목록 획득 (Factory)
        List<LoanLimitAdaptor> adaptors = adaptorFactory.getAdaptors(strategy.getSupportedBanks());

        //한도 조회
        List<LoanLimitAdaptorResponse> adaptorResponses = loanLimitSenderService.inquiry(adaptors, adaptorRequest);

        // 어댑터 응답을 후처리하고 Entity로 변환하여 저장
        adaptorResponses.forEach(adaptorResponse -> {
            // Strategy 후처리
            LoanLimitAdaptorResponse processed = strategy.postProcess(adaptorResponse);

            LoanLimitResult loanLimitResult = processed.success() ?
                    LoanLimitResult.success(
                            processed.partnerCode(),
                            processed.resTimeMs()
                    )
                    :
                    LoanLimitResult.fail(
                            processed.partnerCode(),
                            processed.failReason(),
                            processed.resTimeMs()
                    );

            inquiry.addResult(loanLimitResult);
        });

        return LoanLimitResponse.from(inquiry);
    }
}
