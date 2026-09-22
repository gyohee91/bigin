package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.*;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class LoanLimitServiceTest {

    @InjectMocks
    private LoanLimitService loanLimitService;

    @Mock
    private LoanLimitStrategyFactory strategyFactory;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Mock
    private RedisLockExecutor lockExecutor;

    // Inquiry INSERT + 이벤트 발행은 트랜잭션 분리 이후 이 협력자에게 전부 위임되었다
    // (LoanLimitInquiryPersistenceService#createLoanLimitInquiry 참고).
    // 그래서 이 테스트에서는 repository.save()/ApplicationEventPublisher를 직접 검증하지 않고
    // persistenceService에 올바른 인자로 위임했는지만 검증한다.
    @Mock
    private LoanLimitInquiryPersistenceService persistenceService;

    // requestCompareLoan()의 락 블록은 반환값 없는 Runnable 오버로드를 탄다
    // (action 람다가 조건부로만 throw하고 정상 흐름에선 값 없이 끝나 Supplier로는 타입이 안 맞음)
    private void stubLockAcquired() {
        willAnswer(invocation -> {
            Runnable action = invocation.getArgument(3);
            action.run();
            return null;
        }).given(lockExecutor).execute(anyString(), anyLong(), anyLong(), any(Runnable.class), any(Runnable.class));
    }

    private void stubLockUnavailable() {
        willAnswer(invocation -> {
            Runnable onLockUnavailable = invocation.getArgument(4);
            onLockUnavailable.run();
            return null;
        }).given(lockExecutor).execute(anyString(), anyLong(), anyLong(), any(Runnable.class), any(Runnable.class));
    }

    @Test
    @DisplayName("한도조회 요청 정상처리 - 202 Accepted 즉시 응답")
    void requestCompareLoan_success() {
        // 락 획득 성공 - action 그대로 실행
        this.stubLockAcquired();

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
        LoanLimitAdaptorRequest adaptorRequest = mock(LoanLimitAdaptorRequest.class);
        given(strategy.toAdaptorRequest(any(), any())).willReturn(adaptorRequest);

        // Inquiry INSERT + 이벤트 발행은 persistenceService.createLoanLimitInquiry로 위임된다
        LoanLimitInquiryResponse expectedResponse = LoanLimitInquiryResponse.builder()
                .inquiryNo("LL20260416ANWOW")
                .success(true)
                .build();
        given(persistenceService.createLoanLimitInquiry(
                eq(request), eq(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK)), eq(adaptorRequest)))
                .willReturn(expectedResponse);

        // when
        LoanLimitInquiryResponse response = loanLimitService.requestCompareLoan(request);

        // then - persistenceService가 반환한 응답을 그대로 반환하는지 검증
        assertThat(response).isEqualTo(expectedResponse);

        // then - 정확한 인자(요청, 선정된 금융사, 어댑터 요청)로 위임했는지 검증
        then(persistenceService).should().createLoanLimitInquiry(
                eq(request), eq(List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK)), eq(adaptorRequest));
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
        //given(strategy.filterAvailablePartners(any(), any())).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("현재 조회 가능한 금융사가 없습니다");

        // Inquiry INSERT, 이벤트 발행 모두 없어야함
        then(persistenceService).should(never()).createLoanLimitInquiry(any(), any(), any());
    }

    @Test
    @DisplayName("진행 중인 한도조회 요청이 있으면 중복 요청 방지")
    void requestCompareLoan_inProgressExists_throwsException() {
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

        this.stubLockAcquired();
        given(loanLimitInquiryRepository.existsByUserIdAndLoanTypeAndStatus(1L, LoanType.PERSONAL_CREDIT, InquiryStatus.IN_PROGRESS))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("진행 중인 한도조회가 있습니다.");

        then(persistenceService).should(never()).createLoanLimitInquiry(any(), any(), any());
    }

    @Test
    @DisplayName("오토담보 - Nice DNR 조회 성공 시 정상 처리")
    void requestCompareLoan_auto_niceDnrSuccess() {
        this.stubLockAcquired();

        // given
        LoanLimitRequest request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.AUTO)
                .build();
        LoanLimitStrategy strategy = mock(LoanLimitStrategy.class);
        given(strategyFactory.getStrategy(LoanType.AUTO)).willReturn(strategy);
        given(strategy.requiresExternalData()).willReturn(true);
        given(strategy.fetchExternalData(any())).willReturn(ExternalDataContext.empty());
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.LINE_BANK));
        given(strategy.filterAvailablePartners(any(), any())).willReturn(List.of(PartnerCode.LINE_BANK));
        LoanLimitAdaptorRequest adaptorRequest = mock(LoanLimitAdaptorRequest.class);
        given(strategy.toAdaptorRequest(any(), any())).willReturn(adaptorRequest);

        LoanLimitInquiryResponse expectedResponse = LoanLimitInquiryResponse.builder()
                .inquiryNo("LL20260416ANWOW")
                .success(true)
                .build();
        given(persistenceService.createLoanLimitInquiry(
                eq(request), eq(List.of(PartnerCode.LINE_BANK)), eq(adaptorRequest)))
                .willReturn(expectedResponse);

        // when
        LoanLimitInquiryResponse response = loanLimitService.requestCompareLoan(request);

        // then
        assertThat(response.success()).isEqualTo(true);
        then(strategy).should().fetchExternalData(any());

        // persistenceService에 정확한 인자로 위임했는지 검증
        then(persistenceService).should().createLoanLimitInquiry(
                eq(request), eq(List.of(PartnerCode.LINE_BANK)), eq(adaptorRequest));
    }

    @Test
    @DisplayName("오토담보 - Nice DNR 조회 실패 시 진행 가능 금융사 없으면 예외")
    void requestCompareLoan_auto_niceDnrFailed_throwException() {
        // given
        LoanLimitRequest request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.AUTO)
                .build();
        LoanLimitStrategy strategy = mock(LoanLimitStrategy.class);
        given(strategyFactory.getStrategy(LoanType.AUTO)).willReturn(strategy);
        given(strategy.requiresExternalData()).willReturn(true);

        ExternalDataContext externalDataContext = ExternalDataContext.builder()
                .errors(Map.of("NICE_DNR",
                        ExternalDataError.builder()
                                .code("NICE_DNR_ERROR")
                                .message("NICE DNR 조회 오류")
                                .build())
                )
                .build();
        given(strategy.fetchExternalData(any())).willReturn(externalDataContext);
        given(strategy.getSupportedBanks()).willReturn(List.of(PartnerCode.LINE_BANK));
        given(strategy.filterAvailablePartners(any(), any())).willReturn(List.of());

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class);
        then(persistenceService).should(never()).createLoanLimitInquiry(any(), any(), any());
    }

    @Test
    @DisplayName("동시 요청 - 분산 락 확득 실패 시 즉시 예외")
    void requestCompareLoan_lockFailed_throwsException() {
        this.stubLockUnavailable();

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

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");

        then(loanLimitInquiryRepository).should(never())
                .existsByUserIdAndLoanTypeAndStatus(any(), any(), any());
        then(persistenceService).should(never()).createLoanLimitInquiry(any(), any(), any());
    }

    // 락 대기 중 인터럽트 발생 시 fallback(onLockUnavailable) 실행 + 인터럽트 플래그 복원 로직은
    // DistributedLockExecutor 자체의 책임이 되어 DistributedLockExecutorTest로 옮겼다.
    // (예전엔 인터럽트와 락-실패를 다른 메시지로 구분했지만, executor 도입 후 둘 다
    // 같은 onLockUnavailable("요청이 처리 중입니다...")로 합쳐졌다)

    @Test
    @DisplayName("폴링 - 진행 중 (콜백 미완료) -> productResults 빈 리스트 반환")
    void getInquiryResult_inProgress_emptyProductResults() {
        // given
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .inquiryNo("LL20260410A3F2C891")
                .userId(1L)
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();

        // totalProductCount=3, successProductCount=1 -> 미완료
        ReflectionTestUtils.setField(inquiry, "totalProductCount", 3);
        ReflectionTestUtils.setField(inquiry, "successProductCount", 1);
        ReflectionTestUtils.setField(inquiry, "status", InquiryStatus.IN_PROGRESS);

        given(loanLimitInquiryRepository.findByInquiryNo("LL20260410A3F2C891"))
                .willReturn(Optional.of(inquiry));

        Pageable pageable = PageRequest.of(0, 20);

        // when
        LoanLimitPollingResponse response =
                loanLimitService.getInquiryResult("LL20260410A3F2C891", pageable);

        // then
        assertThat(response.productResults()).isEmpty();
        assertThat(response.progressRate()).isEqualTo(33);
        assertThat(response.allResultReceived()).isFalse();
        assertThat(response.productResults()).isEmpty();

        // 진행 중 -> ProductResult 조회 안됨
        then(loanLimitProductResultRepository).should(never())
                .findProductResultsByInquiryId(any(), any());
    }

    @Test
    @DisplayName("폴링 - 완료 (콜백 전체 수신) -> productResults 반환")
    void getInquiryResult_completed_returnsProductResults() {
        // given
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .inquiryNo("LL20260410A3F2C891")
                .userId(1L)
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
        ReflectionTestUtils.setField(inquiry, "totalProductCount", 2);
        ReflectionTestUtils.setField(inquiry, "successProductCount", 2);
        ReflectionTestUtils.setField(inquiry, "status", InquiryStatus.IN_PROGRESS);

        given(loanLimitInquiryRepository.findByInquiryNo("LL20260410A3F2C891"))
                .willReturn(Optional.of(inquiry));

        List<LoanLimitProductResultDto> dtos = List.of(
                new LoanLimitProductResultDto("LR20260410AAA", PartnerCode.KAKAO_BANK, "TA", LoanLimitResultCode.SUCCESS, 30_000_000L, 3.5),
                new LoanLimitProductResultDto("LR20260410BBB", PartnerCode.LINE_BANK, "TA", LoanLimitResultCode.SUCCESS, 20_000_000L, 4.5)
        );

        Pageable pageable = PageRequest.of(0, 20);
        given(loanLimitProductResultRepository.findProductResultsByInquiryId(any(), eq(pageable)))
                .willReturn(new PageImpl<>(dtos, pageable, dtos.size()));

        // when
        LoanLimitPollingResponse response = loanLimitService.getInquiryResult("LL20260410A3F2C891", pageable);

        // then
        assertThat(response.progressRate()).isEqualTo(100);
        assertThat(response.allResultReceived()).isTrue();
        assertThat(response.productResults()).hasSize(2);
        assertThat(response.productResults().get(0).loReqtNo()).isEqualTo("LR20260410AAA");
    }

    @Test
    @DisplayName("폴링 - 존재하지 않는 inquiryNo -> InvalidRequestException")
    void getInquiryResult_notFound_throwsException() {
        // given
        given(loanLimitInquiryRepository.findByInquiryNo("INVALID"))
                .willReturn(Optional.empty());

        Pageable pageable = PageRequest.of(0, 20);

        // when & then
        assertThatThrownBy(() ->
                loanLimitService.getInquiryResult("INVALID", pageable))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("존재하지 않는 조회이력입니다: INVALID");

        then(loanLimitProductResultRepository).should(never())
                .findProductResultsByInquiryId(any(), any());
    }
}
