package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 파트너 Entity
 *
 * <p>PartnerCode(Enum)는 코드 레벨 식별자로 Adaptor Factory 키 역할 담당
 * Partner(Entity)는 운영 중 변경이 필요한 메타데이터를 DB로 관리
 *
 * <pre>
 * PartnerCode(Enum): Adaptor 분기, 컴파일 타임 타입 안정성
 * Partner(Entity): 노출명, 활성화여부 등
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoanLimitService {
    private final LoanLimitSenderService loanLimitSenderService;
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final LoanLimitStrategyFactory strategyFactory;

    @Transactional
    public LoanLimitResponse requestCompareLoan(LoanLimitRequest request) {
        //진행 중인 조회가 있으면 중복 요청 방지(당일 동일 유형 재조회 제한)
        boolean hasInProgress = loanLimitInquiryRepository.existsByUserIdAndLoanTypeAndStatus(
                request.getUserId(),
                request.getLoanType(),
                InquiryStatus.IN_PROGRESS
        );
        if(hasInProgress) {
            throw new InvalidRequestException("진행 중인 한도조회가 있습니다.");
        }

        LoanLimitStrategy strategy = strategyFactory.getStrategy(request.getLoanType());

        //External 데이터 조회 - Strategy가 알아서 처리
        ExternalDataContext context = strategy.requiresExternalData()
                ? strategy.fetchExternalData(request)
                : ExternalDataContext.empty();

        //LoanLimitInquiry INSERT
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .userId(request.getUserId())
                .name(request.getName())
                .jobType(request.getJobType())
                .jobName(request.getJobName())
                .loanType(request.getLoanType())
                .carNo(request.getCarNo())
                .build();

        loanLimitInquiryRepository.save(inquiry);

        // 어댑터 요청 DTO 변환 (Strategy)
        LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request, context);

        //Strategy: 대출 유형상 가능한 금융사(코드 레벨 고정)
        //DB      : 현재 활성화된 은행 (운영 팀이 배포 없이 제어)
        List<PartnerCode> activePartnerCodes = strategy.getSupportedBanks();
        if(activePartnerCodes.isEmpty())
            throw new InvalidRequestException("현재 조회 가능한 금융사가 없습니다");

        // 한도 조회(백그라운드 비동기 처리)
        // @Async 적용을 위해 별도 Bean(LoanLimitSenderService)으로 분리
        loanLimitSenderService.inquiry(inquiry.getId(), activePartnerCodes, adaptorRequest, strategy);

        return LoanLimitResponse.from(inquiry);
    }
}
