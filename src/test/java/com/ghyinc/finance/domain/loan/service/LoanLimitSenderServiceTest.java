package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.Product;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class LoanLimitSenderServiceTest {
    @InjectMocks
    private LoanLimitSenderService loanLimitSenderService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private LoReqtNoGenerator generator;

    @Mock
    private LoanLimitAdaptorFactory adaptorFactory;

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

    private Product buildProduct(String productCode) {
        Product product = mock(Product.class);
        given(product.getProductCode()).willReturn(productCode);
        return product;
    }

    @Test
    void inquiry_sendSuccess() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        Product product = this.buildProduct("P060100206");
        given(productRepository.findActiveByPartnerCodeAndLoanType(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(product));
        given(generator.generate()).willReturn("LR20260410AAA");

        LoanLimitAdaptor adaptor = mock(LoanLimitAdaptor.class);
        given(adaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.inquireLimit(eq(PartnerCode.LINE_BANK), any()))
                .willReturn(LoanLimitAdaptorResponse.success(PartnerCode.LINE_BANK, 100L));

        LoanLimitAdaptorRequest adaptorRequest = LoanLimitAdaptorRequest.builder()
                .name("윤교희")
                .rrno("9102131234567")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();

        // when
        loanLimitSenderService.inquiry(1L, List.of(PartnerCode.LINE_BANK), adaptorRequest);

        // then
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);

    }
}