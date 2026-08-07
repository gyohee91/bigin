package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.LoanApplyRequest;
import com.ghyinc.finance.domain.loan.dto.LoanApplyResponse;
import com.ghyinc.finance.domain.loan.entity.LoanApply;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.ApplyStatus;
import com.ghyinc.finance.domain.loan.repository.LoanApplyRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoanApplyService {
    private final LoanLimitInquiryRepository loanLimitInquiryRepository;
    private final LoanLimitProductResultRepository loanLimitProductResultRepository;
    private final LoanApplyRepository loanApplyRepository;

    public LoanApplyResponse apply(LoanApplyRequest request) {
        // 한도 이력 검증
        LoanLimitInquiry loanLimitInquiry =
                loanLimitInquiryRepository.findByInquiryNo(request.inquiryNo())
                        .orElseThrow(() -> new InvalidRequestException("한도조회 이력 없음: " + request.inquiryNo()));

        // 선택한 상품 한도결과 검증
        LoanLimitProductResult loanLimitProductResult =
                loanLimitProductResultRepository.findByLoReqtNoAndPartnerCodeAndProductCode(request.loReqtNo(), request.partnerCode(), request.productCode())
                        .orElseThrow(() -> new InvalidRequestException("한도조회 결과 없음: " + request.loReqtNo()));

        if( !loanLimitProductResult.getResultCode().isSuccess() ) {
            throw new InvalidRequestException("한도 부결 상품은 신청 불가: " +
                    loanLimitProductResult.getResultCode().getDescription());
        }

        if( loanApplyRepository.existsByLoReqtNoAndStatusNot(request.loReqtNo(), ApplyStatus.FAILED) ) {
            throw new InvalidRequestException("이미 신청된 상품입니다: " + request.loReqtNo());
        }

        // 대출신청 Entity 생성 및 저장
        LoanApply loanApply = LoanApply.builder()
                .userId(request.userId())
                .loReqtNo(request.loReqtNo())
                .partnerCode(request.partnerCode())
                .productCode(request.productCode())
                .build();

        loanApplyRepository.save(loanApply);

        return LoanApplyResponse.from(loanApply);
    }
}
