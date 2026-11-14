package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.ExternalDataContext;
import com.ghyinc.finance.domain.loan.dto.ExternalDataError;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.PartnerDispatchPlan;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

/**
 * 구 LoanLimitServiceTest 의 "검증 → 외부조회 → 금융사 선정 → 어댑터 요청 변환" 시나리오를
 * 락/영속화와 분리하여 단독 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LoanLimitDispatchPlannerTest {

    @InjectMocks
    private LoanLimitDispatchPlanner dispatchPlanner;

    @Mock
    private LoanLimitStrategyFactory strategyFactory;

    private LoanLimitRequest buildRequest(LoanType loanType) {
        return LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(loanType)
                .build();
    }

    private LoanLimitStrategy strategyFor(LoanType loanType) {
        LoanLimitStrategy strategy = mock(LoanLimitStrategy.class);
        given(strategyFactory.getStrategy(loanType)).willReturn(strategy);
        return strategy;
    }

    @Test
    @DisplayName("외부 데이터가 필요 없는 유형 - 외부 조회 없이 선정된 금융사와 어댑터 요청으로 계획 생성")
    void plan_noExternalData_buildsPlan() {
        LoanLimitRequest request = buildRequest(LoanType.PERSONAL_CREDIT);
        LoanLimitStrategy strategy = strategyFor(LoanType.PERSONAL_CREDIT);
        given(strategy.requiresExternalData()).willReturn(false);
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK));
        given(strategy.filterAvailablePartners(any(), any()))
                .willReturn(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK));
        LoanLimitAdaptorRequest adaptorRequest = mock(LoanLimitAdaptorRequest.class);
        given(strategy.toAdaptorRequest(eq(request), any(ExternalDataContext.class))).willReturn(adaptorRequest);

        PartnerDispatchPlan plan = dispatchPlanner.plan(request);

        assertThat(plan.availablePartnerCodes()).containsExactly(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK);
        assertThat(plan.adaptorRequest()).isSameAs(adaptorRequest);
        then(strategy).should().validate(request);
        then(strategy).should(never()).fetchExternalData(any());
    }

    @Test
    @DisplayName("오토담보 - Nice DNR 조회 성공 시 조회된 컨텍스트로 어댑터 요청을 만든다")
    void plan_auto_niceDnrSuccess_passesFetchedContextToAdaptorRequest() {
        LoanLimitRequest request = buildRequest(LoanType.AUTO);
        LoanLimitStrategy strategy = strategyFor(LoanType.AUTO);
        ExternalDataContext context = ExternalDataContext.empty();
        given(strategy.requiresExternalData()).willReturn(true);
        given(strategy.fetchExternalData(request)).willReturn(context);
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.LINE_BANK));
        given(strategy.filterAvailablePartners(any(), eq(context))).willReturn(List.of(PartnerCode.LINE_BANK));
        LoanLimitAdaptorRequest adaptorRequest = mock(LoanLimitAdaptorRequest.class);
        given(strategy.toAdaptorRequest(request, context)).willReturn(adaptorRequest);

        PartnerDispatchPlan plan = dispatchPlanner.plan(request);

        assertThat(plan.availablePartnerCodes()).containsExactly(PartnerCode.LINE_BANK);
        assertThat(plan.adaptorRequest()).isSameAs(adaptorRequest);
        then(strategy).should().fetchExternalData(request);
    }

    @Test
    @DisplayName("계획에는 지원 금융사 전체가 아니라 외부 데이터 실패로 걸러낸 '진행 가능한' 금융사만 담긴다")
    void plan_usesFilteredPartnersNotSupportedPartners() {
        LoanLimitRequest request = buildRequest(LoanType.AUTO);
        LoanLimitStrategy strategy = strategyFor(LoanType.AUTO);
        given(strategy.requiresExternalData()).willReturn(false);
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK));
        given(strategy.filterAvailablePartners(eq(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK)), any()))
                .willReturn(List.of(PartnerCode.KAKAO_BANK));
        given(strategy.toAdaptorRequest(any(), any())).willReturn(mock(LoanLimitAdaptorRequest.class));

        PartnerDispatchPlan plan = dispatchPlanner.plan(request);

        assertThat(plan.availablePartnerCodes()).containsExactly(PartnerCode.KAKAO_BANK);
    }

    @Test
    @DisplayName("지원 금융사가 없으면 InvalidRequestException, 이후 단계는 호출하지 않는다")
    void plan_noSupportedPartner_throwsException() {
        LoanLimitRequest request = buildRequest(LoanType.PERSONAL_CREDIT);
        LoanLimitStrategy strategy = strategyFor(LoanType.PERSONAL_CREDIT);
        given(strategy.requiresExternalData()).willReturn(false);
        given(strategy.getSupportedBanks()).willReturn(List.of());

        assertThatThrownBy(() -> dispatchPlanner.plan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("현재 조회 가능한 금융사가 없습니다");

        then(strategy).should(never()).filterAvailablePartners(any(), any());
        then(strategy).should(never()).toAdaptorRequest(any(), any());
    }

    @Test
    @DisplayName("오토담보 - Nice DNR 조회 실패로 진행 가능한 금융사가 없으면 오류 메시지를 포함한 예외")
    void plan_auto_niceDnrFailed_noAvailablePartner_throwsExceptionWithReason() {
        LoanLimitRequest request = buildRequest(LoanType.AUTO);
        LoanLimitStrategy strategy = strategyFor(LoanType.AUTO);
        ExternalDataContext failedContext = ExternalDataContext.builder()
                .errors(Map.of("NICE_DNR",
                        ExternalDataError.builder()
                                .code("NICE_DNR_ERROR")
                                .message("NICE DNR 조회 오류")
                                .build()))
                .build();
        given(strategy.requiresExternalData()).willReturn(true);
        given(strategy.fetchExternalData(request)).willReturn(failedContext);
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.LINE_BANK));
        given(strategy.filterAvailablePartners(any(), any())).willReturn(List.of());

        assertThatThrownBy(() -> dispatchPlanner.plan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("현재 조회 가능한 금융사가 없습니다")
                .hasMessageContaining("NICE DNR 조회 오류");

        then(strategy).should(never()).toAdaptorRequest(any(), any());
    }

    @Test
    @DisplayName("입력값 검증에 실패하면 외부 조회를 하지 않고 예외를 그대로 전파")
    void plan_validationFails_doesNotFetchExternalData() {
        LoanLimitRequest request = buildRequest(LoanType.AUTO);
        LoanLimitStrategy strategy = strategyFor(LoanType.AUTO);
        willThrow(new InvalidRequestException("차량번호는 필수입니다")).given(strategy).validate(request);

        assertThatThrownBy(() -> dispatchPlanner.plan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("차량번호는 필수입니다");

        then(strategy).should(never()).fetchExternalData(any());
        then(strategy).should(never()).getSupportedBanks();
    }
}
