package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.*;
import com.ghyinc.finance.domain.loan.factory.LoanLimitStrategyFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.strategy.LoanLimitStrategy;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
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
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;
    
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private RedissonClient redissonClient;

    private RLock rLock;

    /**
     * 락 관련 given 설정
     */
    private void givenLockAcquired() throws InterruptedException {
        rLock = mock(RLock.class);
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(true); // 기본: 락 획득 성공
        given(rLock.isHeldByCurrentThread()).willReturn(true);
    }

    @Test
    @DisplayName("한도조회 요청 정상처리 - 202 Accepted 즉시 응답")
    void requestCompareLoan_success() throws InterruptedException {
        // given
        this.givenLockAcquired();

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
        given(generator.generate("LL")).willReturn("LL20260416ANWOW");
        given(strategy.toAdaptorRequest(any(), any())).willReturn(mock(LoanLimitAdaptorRequest.class));

        given(loanLimitInquiryRepository.save(any(LoanLimitInquiry.class)))
                .willAnswer(invocation -> {
                    LoanLimitInquiry inquiry = invocation.getArgument(0);
                    ReflectionTestUtils.setField(inquiry, "id", 1L);
                    return inquiry;
                });

        // when
        LoanLimitInquiryResponse response = loanLimitService.requestCompareLoan(request);

        // then - Inquiry INSERT 검증
        assertThat(response.success()).isEqualTo(true);
        then(loanLimitInquiryRepository).should().save(any(LoanLimitInquiry.class));

        // then - senderService 직접 호출 대신 이벤트 발행 검증
        ArgumentCaptor<LoanLimitInquiryCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(LoanLimitInquiryCreatedEvent.class);
        then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());

        LoanLimitInquiryCreatedEvent capturedEvent = eventCaptor.getValue();
        assertThat(capturedEvent.id()).isEqualTo(1L);
        assertThat(capturedEvent.activePartnerCodes())
                .containsExactly(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK);
        //then(loanLimitSenderService).should().inquiry(anyLong(), anyList(), any());
    }

    @Test
    @DisplayName("활성화된 금융사가 없으면 InvalidRequestException 발생")
    void requestCompareLoan_noActivePartner_throwException() throws InterruptedException {
        // given
        this.givenLockAcquired();

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
        then(loanLimitInquiryRepository).should(never()).save(any());
        then(applicationEventPublisher).should(never()).publishEvent(any());
        //then(loanLimitSenderService).should(never()).inquiry(anyLong(), anyList(), any());
    }

    @Test
    @DisplayName("진행 중인 한도조회 요청이 있으면 중복 요청 방지")
    void requestCompareLoan_inProgressExists_throwsException() throws InterruptedException {
        // given
        this.givenLockAcquired();

        LoanLimitRequest request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
        
        given(loanLimitInquiryRepository.existsByUserIdAndLoanTypeAndStatus(1L, LoanType.PERSONAL_CREDIT, InquiryStatus.IN_PROGRESS))
                .willReturn(true);

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("진행 중인 한도조회가 있습니다.");

        then(loanLimitInquiryRepository).should(never()).save(any());
        then(applicationEventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("오토담보 - Nice DNR 조회 성공 시 정상 처리")
    void requestCompareLoan_auto_niceDnrSuccess() throws InterruptedException {
        // given
        this.givenLockAcquired();

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
        given(generator.generate("LL")).willReturn("LL20260416ANWOW");
        given(strategy.toAdaptorRequest(any(), any())).willReturn(mock(LoanLimitAdaptorRequest.class));

        given(loanLimitInquiryRepository.save(any(LoanLimitInquiry.class)))
                .willAnswer(invocation -> {
                    LoanLimitInquiry inquiry = invocation.getArgument(0);
                    ReflectionTestUtils.setField(inquiry, "id", 1L);
                    return inquiry;
                });

        // when
        LoanLimitInquiryResponse response = loanLimitService.requestCompareLoan(request);

        // then
        assertThat(response.success()).isEqualTo(true);
        then(strategy).should().fetchExternalData(any());

        // 이벤트 발행 검증
        ArgumentCaptor<LoanLimitInquiryCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(LoanLimitInquiryCreatedEvent.class);
        then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());

        assertThat(eventCaptor.getValue().id()).isEqualTo(1L);
        assertThat(eventCaptor.getValue().activePartnerCodes())
                .containsExactly(PartnerCode.LINE_BANK);
    }

    @Test
    @DisplayName("오토담보 - Nice DNR 조회 실패 시 진행 가능 금융사 없으면 예외")
    void requestCompareLoan_auto_niceDnrFailed_throwException() throws InterruptedException {
        // given
        this.givenLockAcquired();

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
        //given(generator.generate("LL")).willReturn("LL20260416ANWOW");

        // when & then
        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class);
        then(applicationEventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("동시 요청 - 분산 락 확득 실패 시 즉시 예외")
    void requestCompareLoan_lockFailed_throwsException() throws InterruptedException {
        // given
        rLock = mock(RLock.class);
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(false);
        given(rLock.isHeldByCurrentThread()).willReturn(false);

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
        then(loanLimitInquiryRepository).should(never()).save(any());
        then(applicationEventPublisher).should(never()).publishEvent(any());
    }

    @Test
    @DisplayName("동시 요청 - 락 대기 중 인터럽트 발생 시 예외")
    void requestCompareLoan_interrupted_throwsException() throws InterruptedException {
        // given
        rLock = mock(RLock.class);
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any())).willThrow(new InterruptedException());
        given(rLock.isHeldByCurrentThread()).willReturn(false);

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
                .hasMessage("요청 처리 중 오류가 발생했습니다.");

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

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
