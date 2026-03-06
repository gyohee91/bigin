package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

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
    private final PartnerRepository partnerRepository;
    private final LoanLimitStrategyFactory strategyFactory;
    private final LoanLimitAdaptorFactory adaptorFactory;

    @Transactional
    public LoanLimitResponse requestCompareLoan(LoanLimitRequest request) {
        LoanLimitStrategy strategy = strategyFactory.getStrategy(request.getLoanType());

        String loReqtNo = this.getLoReqtNo();
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .loReqtNo(loReqtNo)
                .loanType(request.getLoanType())
                .build();

        loanLimitInquiryRepository.save(inquiry);

        // 어댑터 요청 DTO 변환 (Strategy)
        LoanLimitAdaptorRequest adaptorRequest = strategy.toAdaptorRequest(request);

        //Strategy: 대출 유형상 가능한 금융사(코드 레벨 고정)
        //DB      : 현재 활성화된 은행 (운영 팀이 배포 없이 제어)
        List<PartnerCode> activePartnerCodes = partnerRepository
                .findActiveByPartnerCodes(strategy.getSupportedBanks())
                .stream()
                .map(Partner::getPartnerCode)
                .toList();
        if(activePartnerCodes.isEmpty())
            throw new InvalidRequestException("현재 조회 가능한 금융사가 없습니다");

        // 지원 은행 목록 조회 (Strategy) -> 어댑터 목록 획득 (Factory)
        List<LoanLimitAdaptor> adaptors = adaptorFactory.getAdaptors(activePartnerCodes);

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

    private String getLoReqtNo() {
        return "".concat(UUID.randomUUID().toString());
    }
}
