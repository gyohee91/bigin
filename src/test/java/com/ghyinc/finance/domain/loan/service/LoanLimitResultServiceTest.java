package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.*;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class LoanLimitResultServiceTest {

    @InjectMocks
    private LoanLimitResultService loanLimitResultService;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    private LoanLimitInquiry buildInquiry() {
        return LoanLimitInquiry.builder()
                .userId(1L)
                .name("윤교희")
                .ci("")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
    }

    private LoanLimitProductResult buildProductResult(LoanLimitInquiry inquiry, String loReqtNo, String productCode) {
        LoanLimitProductResult loanLimitProductResult = LoanLimitProductResult.builder()
                .loanLimitInquiry(inquiry)
                .loReqtNo(loReqtNo)
                .partnerCode(PartnerCode.LINE_BANK)
                .productCode(productCode)
                .build();
        // SEND_SUCCESS 상태로 변경 (전송 성공 후 콜백 대기 상태)
        loanLimitProductResult.sendSuccess();
        return loanLimitProductResult;
    }

    private LoanLimitResultRequest.PreScrResultList buildSuccessItem(String loReqtNo, String productCode) {
        return LoanLimitResultRequest.PreScrResultList.builder()
                .loReqtNo(loReqtNo)
                .productCode(productCode)
                .resultCode(LoanLimitResultCode.SUCCESS)
                .amount(30_000_000L)
                .interestRate(4.5)
                .build();
    }

    private LoanLimitResultRequest buildRequest(List<LoanLimitResultRequest.PreScrResultList> items) {
        return LoanLimitResultRequest.builder()
                .preScrResultList(items)
                .build();
    }

    @Test
    @DisplayName("콜백 정상 수신")
    void responseCompareLoanResult() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        LoanLimitResultRequest.PreScrResultList preScrResultList = this.buildSuccessItem("LR20260410AAA", "P060100206");

        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));
        given(loanLimitProductResultRepository.findInquiryByLoReqtNoAndProduceCodeWithLock("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(inquiry));

        // when
        loanLimitResultService.process(PartnerCode.LINE_BANK, this.buildRequest(List.of(preScrResultList)));

        // then
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.SUCCESS);
        assertThat(productResult.getAmount()).isEqualTo(30_000_000);
        assertThat(productResult.getResultCode()).isEqualTo(LoanLimitResultCode.SUCCESS);
    }
}