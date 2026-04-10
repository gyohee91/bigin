package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LoanLimitServiceTest {

    @InjectMocks
    private LoanLimitService loanLimitService;

    @Mock
    private LoanLimitStrategyFactory strategyFactory;

    @Mock
    private LoReqtNoGenerator generator;

    @Mock
    private PartnerRepository partnerRepository;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private LoanLimitSenderService loanLimitSenderService;

    @Test
    @DisplayName("한도조회 요청 정상처리 - 202 Accepted 즉시 응답")
    void requestCompareLoan_success() {
        // given
        LoanLimitRequest request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();

        LoanLimitStrategy strategy = mock(LoanLimitStrategy.class);
        given(strategyFactory.getStrategy(LoanType.PERSONAL_CREDIT)).willReturn(strategy);
        given(strategy.requiresExternalData()).willReturn(false);
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK));
        given(strategy.filterAvailablePartners(any(), any())).willReturn(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK));
        given(strategy.toAdaptorRequest(any(), any())).willReturn(mock(LoanLimitAdaptorRequest.class));

        given(loanLimitInquiryRepository.save(any(LoanLimitInquiry.class)))
                .willAnswer(invocation -> {
                    LoanLimitInquiry inquiry = invocation.getArgument(0);
                    ReflectionTestUtils.setField(inquiry, "id", 1L);
                    return inquiry;
                });

        // when
        LoanLimitResponse response = loanLimitService.requestCompareLoan(request);

        // then
        assertThat(response.isSuccess()).isEqualTo(true);

        then(loanLimitInquiryRepository).should().save(any(LoanLimitInquiry.class));
        then(loanLimitSenderService).should().inquiry(anyLong(), anyList(), any(), any());
    }

    @Test
    @DisplayName("활성화된 금융사가 없으면 InvalidRequestException 발생")
    void requestCompareLoan_noActivePartner_throwException() {
        // given
        LoanLimitRequest request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
        LoanLimitStrategy strategy = mock(LoanLimitStrategy.class);
        given(strategyFactory.getStrategy(LoanType.PERSONAL_CREDIT)).willReturn(strategy);
        given(strategy.requiresExternalData()).willReturn(false);
        given(strategy.getSupportedBanks()).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("현재 조회 가능한 금융사가 없습니다");
        then(loanLimitSenderService).should(never()).inquiry(anyLong(), anyList(), any(), any());
    }

    @Test
    void requestCompareLoan() {
    }
}