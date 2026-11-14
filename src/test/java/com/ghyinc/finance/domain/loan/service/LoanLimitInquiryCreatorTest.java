package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.LoanLimitInquiryResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.PartnerDispatchPlan;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LoanLimitInquiryCreatorTest {

    private static final String DEDUP_KEY = "1:PERSONAL_CREDIT:20260101";
    private static final String INQUIRY_NO = "LL20260101ACCEPTED";
    private static final String DUPLICATE_MESSAGE = "오늘은 이미 한도조회를 진행했습니다.";

    @InjectMocks
    private LoanLimitInquiryCreator inquiryCreator;

    @Mock
    private LoanLimitInquiryPersistenceService persistenceService;

    @Mock
    private LoanLimitInquiryClaimService claimService;

    @Mock
    private LoanLimitDispatchPlanner dispatchPlanner;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    private LoanLimitRequest request;
    private LoanLimitAdaptorRequest adaptorRequest;
    private PartnerDispatchPlan plan;

    @BeforeEach
    void setUp() {
        request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("ci-value")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
        adaptorRequest = mock(LoanLimitAdaptorRequest.class);
        plan = new PartnerDispatchPlan(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK), adaptorRequest);
    }

    // ─── processWithRedisLock (1차) ───────────────────────────────────────────

    @Test
    @DisplayName("1차 - 계획을 세운 뒤 dedupKey 와 함께 INSERT 에 위임하고 응답을 그대로 반환")
    void processWithRedisLock_success_createsInquiryWithDedupKey() {
        LoanLimitInquiryResponse expected = LoanLimitInquiryResponse.builder().inquiryNo(INQUIRY_NO).success(true).build();
        given(dispatchPlanner.plan(request)).willReturn(plan);
        given(persistenceService.createLoanLimitInquiry(
                request, plan.availablePartnerCodes(), adaptorRequest, DEDUP_KEY)).willReturn(expected);

        LoanLimitInquiryResponse response = inquiryCreator.processWithRedisLock(request, DEDUP_KEY);

        assertThat(response).isEqualTo(expected);
        then(claimService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("1차 - INSERT 가 dedup_key unique 위반이고 실제로 활성 건이 있으면 '이미 진행' 예외로 변환")
    void processWithRedisLock_uniqueViolation_translatedToDuplicate() {
        given(dispatchPlanner.plan(request)).willReturn(plan);
        given(persistenceService.createLoanLimitInquiry(any(), any(), any(), any()))
                .willThrow(new DataIntegrityViolationException("uk_inquiry_dedup_key"));
        given(loanLimitInquiryRepository.existsByDedupKey(DEDUP_KEY)).willReturn(true);

        assertThatThrownBy(() -> inquiryCreator.processWithRedisLock(request, DEDUP_KEY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(DUPLICATE_MESSAGE);
    }

    @Test
    @DisplayName("1차 - 중복이 아닌 다른 제약 위반(예: inquiryNo 충돌)은 숨기지 않고 원본 예외를 전파")
    void processWithRedisLock_otherIntegrityViolation_rethrown() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("uk_inquiry_no");
        given(dispatchPlanner.plan(request)).willReturn(plan);
        given(persistenceService.createLoanLimitInquiry(any(), any(), any(), any())).willThrow(original);
        given(loanLimitInquiryRepository.existsByDedupKey(DEDUP_KEY)).willReturn(false);

        assertThatThrownBy(() -> inquiryCreator.processWithRedisLock(request, DEDUP_KEY))
                .isSameAs(original);
    }

    @Test
    @DisplayName("1차 - 계획 수립(검증/외부조회/금융사 선정) 실패 시 INSERT 를 시도하지 않는다")
    void processWithRedisLock_planFails_doesNotInsert() {
        given(dispatchPlanner.plan(request)).willThrow(new InvalidRequestException("현재 조회 가능한 금융사가 없습니다"));

        assertThatThrownBy(() -> inquiryCreator.processWithRedisLock(request, DEDUP_KEY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("현재 조회 가능한 금융사가 없습니다");

        then(persistenceService).shouldHaveNoInteractions();
    }

    // ─── processWithDbClaim (2차: Redis 장애) ─────────────────────────────────

    @Test
    @DisplayName("2차 - 선점 → 계획 수립 → 완료 순서로 진행하고 완료 결과를 반환 (선점 반납 없음)")
    void processWithDbClaim_success_claimsThenPlansThenCompletes() {
        LoanLimitInquiryResponse expected = LoanLimitInquiryResponse.builder().inquiryNo(INQUIRY_NO).success(true).build();
        given(claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY)).willReturn(INQUIRY_NO);
        given(dispatchPlanner.plan(request)).willReturn(plan);
        given(persistenceService.completeInquiry(
                INQUIRY_NO, request, plan.availablePartnerCodes(), adaptorRequest)).willReturn(expected);

        LoanLimitInquiryResponse response = inquiryCreator.processWithDbClaim(request, DEDUP_KEY);

        assertThat(response).isEqualTo(expected);
        // 외부 조회(plan)보다 선점(claim)이 반드시 먼저여야 한다 - 중복 요청의 외부 호출 방지
        InOrder inOrder = inOrder(claimService, dispatchPlanner, persistenceService);
        inOrder.verify(claimService).claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY);
        inOrder.verify(dispatchPlanner).plan(request);
        inOrder.verify(persistenceService).completeInquiry(
                INQUIRY_NO, request, plan.availablePartnerCodes(), adaptorRequest);
        then(claimService).should(never()).abort(any());
        then(persistenceService).should(never()).createLoanLimitInquiry(any(), any(), any(), any());
    }

    @Test
    @DisplayName("2차 - 선점이 dedup_key unique 위반(이미 활성 건 존재)이면 외부 조회 없이 '이미 진행' 예외")
    void processWithDbClaim_claimDuplicate_rejectedBeforeExternalCall() {
        given(claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY))
                .willThrow(new DataIntegrityViolationException("uk_inquiry_dedup_key"));
        given(loanLimitInquiryRepository.existsByDedupKey(DEDUP_KEY)).willReturn(true);

        assertThatThrownBy(() -> inquiryCreator.processWithDbClaim(request, DEDUP_KEY))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage(DUPLICATE_MESSAGE);

        then(dispatchPlanner).shouldHaveNoInteractions();   // 외부 조회(Nice/Coocon)가 일어나지 않아야 한다
        then(persistenceService).shouldHaveNoInteractions();
        then(claimService).should(never()).abort(any());     // 선점하지 못했으므로 반납할 것도 없다
    }

    @Test
    @DisplayName("2차 - 선점 단계의 다른 제약 위반은 숨기지 않고 원본 예외를 전파")
    void processWithDbClaim_claimOtherIntegrityViolation_rethrown() {
        DataIntegrityViolationException original = new DataIntegrityViolationException("uk_inquiry_no");
        given(claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY)).willThrow(original);
        given(loanLimitInquiryRepository.existsByDedupKey(DEDUP_KEY)).willReturn(false);

        assertThatThrownBy(() -> inquiryCreator.processWithDbClaim(request, DEDUP_KEY))
                .isSameAs(original);

        then(dispatchPlanner).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("2차 - 계획 수립(검증/외부조회) 실패 시 선점을 반납(abort)하고 예외를 그대로 전파")
    void processWithDbClaim_planFails_abortsClaim() {
        InvalidRequestException planFailure = new InvalidRequestException("NICE DNR 조회 오류");
        given(claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY)).willReturn(INQUIRY_NO);
        given(dispatchPlanner.plan(request)).willThrow(planFailure);

        assertThatThrownBy(() -> inquiryCreator.processWithDbClaim(request, DEDUP_KEY))
                .isSameAs(planFailure);

        then(claimService).should().abort(INQUIRY_NO);
        then(persistenceService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("2차 - 완료 단계 실패(예: 정리 배치가 먼저 FAILED 처리) 시에도 선점을 반납하고 예외 전파")
    void processWithDbClaim_completeFails_abortsClaim() {
        InvalidRequestException timeout = new InvalidRequestException("요청 처리 시간이 초과되었습니다. 다시 시도해 주세요.");
        given(claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY)).willReturn(INQUIRY_NO);
        given(dispatchPlanner.plan(request)).willReturn(plan);
        given(persistenceService.completeInquiry(any(), any(), any(), any())).willThrow(timeout);

        assertThatThrownBy(() -> inquiryCreator.processWithDbClaim(request, DEDUP_KEY))
                .isSameAs(timeout);

        then(claimService).should().abort(INQUIRY_NO);
    }

    @Test
    @DisplayName("2차 - 선점 반납(abort) 자체가 실패해도 원래 예외를 유지하고 abort 예외는 suppressed 로 남긴다")
    void processWithDbClaim_abortAlsoFails_keepsOriginalException() {
        InvalidRequestException planFailure = new InvalidRequestException("NICE DNR 조회 오류");
        IllegalStateException abortFailure = new IllegalStateException("DB 장애");
        given(claimService.claim(1L, LoanType.PERSONAL_CREDIT, DEDUP_KEY)).willReturn(INQUIRY_NO);
        given(dispatchPlanner.plan(request)).willThrow(planFailure);
        willThrow(abortFailure).given(claimService).abort(INQUIRY_NO);

        assertThatThrownBy(() -> inquiryCreator.processWithDbClaim(request, DEDUP_KEY))
                .isSameAs(planFailure);

        assertThat(planFailure.getSuppressed()).containsExactly(abortFailure);
    }
}
