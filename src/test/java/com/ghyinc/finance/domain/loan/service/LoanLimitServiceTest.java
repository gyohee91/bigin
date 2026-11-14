package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.LoanLimitInquiryResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitPollingResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.global.exception.LockUnavailableException;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * LoanLimitService 는 이제 "사전 중복 검사 → Redis 락 시도 → (Redis 장애 시) DB 선점 전환"
 * 오케스트레이션만 담당한다. 검증/외부조회/금융사 선정은 {@link LoanLimitDispatchPlannerTest},
 * INSERT·선점 로직은 {@link LoanLimitInquiryCreatorTest} 에서 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class LoanLimitServiceTest {

    private static final String DEDUP_KEY_PATTERN = "1:PERSONAL_CREDIT:\\d{8}";
    private static final String LOCK_KEY = "loan:request:lock:1:PERSONAL_CREDIT";

    @InjectMocks
    private LoanLimitService loanLimitService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Mock
    private RedisLockExecutor lockExecutor;

    @Mock
    private LoanLimitInquiryCreator inquiryCreator;

    private LoanLimitRequest buildRequest() {
        return LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("wEi9oYSuekQGxT9MV4rKHG4CO+Zrp+onhLIIuembI8jx/0PLF5Ne3oMBxvUFlN4UmsgjeNErZfmpCVUFH")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
    }

    // 락 획득 성공 - action 람다를 그대로 실행한다
    @SuppressWarnings("unchecked")
    private void stubLockAcquired() {
        willAnswer(invocation -> {
            Supplier<Object> action = invocation.getArgument(3);
            return action.get();
        }).given(lockExecutor).execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class));
    }

    // 락 획득 실패(다른 요청이 보유 중) - onLockUnavailable 람다를 실행한다
    @SuppressWarnings("unchecked")
    private void stubLockBusy() {
        willAnswer(invocation -> {
            Supplier<Object> onLockUnavailable = invocation.getArgument(4);
            return onLockUnavailable.get();
        }).given(lockExecutor).execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class));
    }

    // Redis 장애 - RedisLockExecutor 가 LockUnavailableException 을 던진다
    @SuppressWarnings("unchecked")
    private void stubRedisDown() {
        willThrow(new LockUnavailableException("Redis 장애", new RuntimeException("connection refused")))
                .given(lockExecutor)
                .execute(anyString(), anyLong(), anyLong(), any(Supplier.class), any(Supplier.class));
    }

    // ─── requestCompareLoan ───────────────────────────────────────────────────

    @Test
    @DisplayName("한도조회 요청 - 락 획득 시 Redis 락 흐름(processWithRedisLock)에 위임하고 응답을 그대로 반환")
    @SuppressWarnings("unchecked")
    void requestCompareLoan_lockAcquired_delegatesToRedisLockFlow() {
        this.stubLockAcquired();
        LoanLimitRequest request = buildRequest();
        LoanLimitInquiryResponse expected = LoanLimitInquiryResponse.builder()
                .inquiryNo("LL20260416ANWOW").success(true).build();
        given(inquiryCreator.processWithRedisLock(eq(request), anyString())).willReturn(expected);

        LoanLimitInquiryResponse response = loanLimitService.requestCompareLoan(request);

        assertThat(response).isEqualTo(expected);
        // 락 키·대기 0초·lease 15초
        then(lockExecutor).should().execute(eq(LOCK_KEY), eq(0L), eq(15L), any(Supplier.class), any(Supplier.class));
        then(inquiryCreator).should(never()).processWithDbClaim(any(), any());
    }

    @Test
    @DisplayName("한도조회 요청 - 사전 검사와 생성 흐름에 동일한 dedupKey(userId:loanType:yyyyMMdd)를 전달")
    void requestCompareLoan_usesSameDedupKeyForPrecheckAndCreation() {
        this.stubLockAcquired();
        LoanLimitRequest request = buildRequest();

        loanLimitService.requestCompareLoan(request);

        ArgumentCaptor<String> precheckKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> creationKey = ArgumentCaptor.forClass(String.class);
        then(loanLimitInquiryRepository).should().existsByDedupKey(precheckKey.capture());
        then(inquiryCreator).should().processWithRedisLock(eq(request), creationKey.capture());

        assertThat(precheckKey.getValue()).matches(DEDUP_KEY_PATTERN);
        assertThat(creationKey.getValue()).isEqualTo(precheckKey.getValue());
    }

    @Test
    @DisplayName("한도조회 요청 - 오늘 이미 접수/진행/완료된 건이 있으면 락·생성 흐름 없이 즉시 거절")
    void requestCompareLoan_dedupKeyAlreadyExists_rejectsWithoutLock() {
        given(loanLimitInquiryRepository.existsByDedupKey(anyString())).willReturn(true);

        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(buildRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("오늘은 이미 한도조회를 진행했습니다.");

        then(lockExecutor).shouldHaveNoInteractions();
        then(inquiryCreator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("동시 요청 - 분산 락 획득 실패(다른 요청이 처리 중)면 즉시 예외, 생성 흐름·DB 선점 전환 없음")
    void requestCompareLoan_lockBusy_throwsAndDoesNotFallBack() {
        this.stubLockBusy();

        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(buildRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");

        then(inquiryCreator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Redis 장애(LockUnavailableException) - DB 선점 흐름(processWithDbClaim)으로 전환")
    void requestCompareLoan_redisDown_fallsBackToDbClaim() {
        this.stubRedisDown();
        LoanLimitRequest request = buildRequest();
        LoanLimitInquiryResponse expected = LoanLimitInquiryResponse.builder()
                .inquiryNo("LL20260416DBCLAIM").success(true).build();
        given(inquiryCreator.processWithDbClaim(eq(request), anyString())).willReturn(expected);

        LoanLimitInquiryResponse response = loanLimitService.requestCompareLoan(request);

        assertThat(response).isEqualTo(expected);
        then(inquiryCreator).should(never()).processWithRedisLock(any(), any());
    }

    @Test
    @DisplayName("Redis 장애 전환 시에도 사전 검사와 DB 선점에 동일한 dedupKey 전달")
    void requestCompareLoan_redisDown_usesSameDedupKey() {
        this.stubRedisDown();
        LoanLimitRequest request = buildRequest();

        loanLimitService.requestCompareLoan(request);

        ArgumentCaptor<String> precheckKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> claimKey = ArgumentCaptor.forClass(String.class);
        then(loanLimitInquiryRepository).should().existsByDedupKey(precheckKey.capture());
        then(inquiryCreator).should().processWithDbClaim(eq(request), claimKey.capture());

        assertThat(claimKey.getValue()).isEqualTo(precheckKey.getValue()).matches(DEDUP_KEY_PATTERN);
    }

    @Test
    @DisplayName("Redis 장애 전환 후 DB 선점에서 중복이면 예외를 그대로 전파")
    void requestCompareLoan_redisDown_dbClaimDuplicate_propagates() {
        this.stubRedisDown();
        LoanLimitRequest request = buildRequest();
        given(inquiryCreator.processWithDbClaim(eq(request), anyString()))
                .willThrow(new InvalidRequestException("오늘은 이미 한도조회를 진행했습니다."));

        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("오늘은 이미 한도조회를 진행했습니다.");
    }

    @Test
    @DisplayName("Redis 락 흐름 안에서 발생한 비즈니스 예외는 DB 선점으로 전환하지 않고 그대로 전파")
    void requestCompareLoan_redisFlowBusinessError_doesNotFallBack() {
        this.stubLockAcquired();
        LoanLimitRequest request = buildRequest();
        given(inquiryCreator.processWithRedisLock(eq(request), anyString()))
                .willThrow(new InvalidRequestException("현재 조회 가능한 금융사가 없습니다"));

        assertThatThrownBy(() -> loanLimitService.requestCompareLoan(request))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("현재 조회 가능한 금융사가 없습니다");

        then(inquiryCreator).should(never()).processWithDbClaim(any(), any());
    }

    // 락 대기 중 인터럽트 발생 시 fallback(onLockUnavailable) 실행 + 인터럽트 플래그 복원 로직은
    // RedisLockExecutor 자체의 책임이므로 RedisLockExecutorTest 에서 검증한다.

    // ─── getInquiryResult (폴링) ──────────────────────────────────────────────

    @Test
    @DisplayName("폴링 - 진행 중 (콜백 일부 수신) -> 현재까지 수신된 productResults 반환")
    void getInquiryResult_inProgress_returnsReceivedProductResults() {
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

        // 3개 상품 중 1개만 콜백 수신 (SUCCESS), 나머지는 아직 대기 (SEND_SUCCESS)
        List<LoanLimitProductResultDto> dtos = List.of(
                new LoanLimitProductResultDto("LR20260410AAA", PartnerCode.KAKAO_BANK, "TA", PartnerInquiryStatus.SUCCESS, LoanLimitResultCode.SUCCESS, 30_000_000L, 3.5),
                new LoanLimitProductResultDto("LR20260410BBB", PartnerCode.LINE_BANK, "TA", PartnerInquiryStatus.SEND_SUCCESS, null, null, 0.0),
                new LoanLimitProductResultDto("LR20260410CCC", PartnerCode.TOSS_BANK, "TA", PartnerInquiryStatus.SEND_SUCCESS, null, null, 0.0)
        );

        Pageable pageable = PageRequest.of(0, 20);
        given(loanLimitProductResultRepository.findProductResultsByInquiryId(any(), eq(pageable)))
                .willReturn(new PageImpl<>(dtos, pageable, dtos.size()));

        // when
        LoanLimitPollingResponse response =
                loanLimitService.getInquiryResult("LL20260410A3F2C891", pageable);

        // then - 진행 중이어도 수신된 결과 목록이 내려간다
        assertThat(response.progressRate()).isEqualTo(33);
        assertThat(response.allResultReceived()).isFalse();
        assertThat(response.productResults()).hasSize(3);
        assertThat(response.productResults().get(0).status()).isEqualTo(PartnerInquiryStatus.SUCCESS);
        then(loanLimitProductResultRepository).should()
                .findProductResultsByInquiryId(any(), eq(pageable));
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
                new LoanLimitProductResultDto("LR20260410AAA", PartnerCode.KAKAO_BANK, "TA", PartnerInquiryStatus.SEND_SUCCESS, LoanLimitResultCode.SUCCESS, 30_000_000L, 3.5),
                new LoanLimitProductResultDto("LR20260410BBB", PartnerCode.LINE_BANK, "TA", PartnerInquiryStatus.SEND_SUCCESS, LoanLimitResultCode.SUCCESS, 20_000_000L, 4.5)
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
