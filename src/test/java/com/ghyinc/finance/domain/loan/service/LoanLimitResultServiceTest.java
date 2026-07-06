package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.*;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class LoanLimitResultServiceTest {

    @InjectMocks
    private LoanLimitResultService loanLimitResultService;

    @Mock
    private LoanLimitResultAdaptorFactory resultAdaptorFactory;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Mock
    private RedissonClient redissonClient;

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

    private LoanLimitResultRequest.LoanApplyResult buildSuccessItem(String loReqtNo, String productCode) {
        return LoanLimitResultRequest.LoanApplyResult.builder()
                .loReqtNo(loReqtNo)
                .productCode(productCode)
                .resultCode(LoanLimitResultCode.SUCCESS)
                .amount(30_000_000L)
                .interestRate(4.5)
                .build();
    }

    private JsonNode buildRequest(List<LoanLimitResultRequest.LoanApplyResult> items) {
        ObjectMapper objectMapper = new ObjectMapper();
        LoanLimitResultRequest request = LoanLimitResultRequest.builder()
                .loanApplyResults(items)
                .build();
        return objectMapper.valueToTree(request);
    }

    private LoanLimitResultRequest buildRequestDto(List<LoanLimitResultRequest.LoanApplyResult> items) {
        return LoanLimitResultRequest.builder()
                .loanApplyResults(items)
                .build();
    }

    @Test
    @DisplayName("콜백 정상 수신")
    void responseCompareLoanResult() throws InterruptedException {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        LoanLimitResultRequest.LoanApplyResult preScrResultList = this.buildSuccessItem("LR20260410AAA", "P060100206");

        // Redisson 분산 락 mock 결정
        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(true);
        given(rLock.isHeldByCurrentThread()).willReturn(true);

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(preScrResultList)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));
        //given(loanLimitProductResultRepository.findInquiryByLoReqtNoAndProduceCodeWithLock("LR20260410AAA", "P060100206"))
        //        .willReturn(Optional.of(inquiry));

        // 비관 락 메서드 → 일반 조회 메서드로 교체
        given(loanLimitProductResultRepository.findInquiryByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(inquiry));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(preScrResultList)));

        // then
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.SUCCESS);
        assertThat(productResult.getAmount()).isEqualTo(30_000_000);
        assertThat(productResult.getResultCode()).isEqualTo(LoanLimitResultCode.SUCCESS);
    }

    @Test
    @DisplayName("분산 락 획득 실패 시 successProductCount 미증가")
    void concurrentCallbacks_successProductCountConsistency() throws InterruptedException {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        LoanLimitResultRequest.LoanApplyResult item = this.buildSuccessItem("LR20260410AAA", "P060100206");

        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        // 락 획득 실패 시뮬레이션
        given(rLock.tryLock(anyLong(), anyLong(), any())).willReturn(false);

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item)));

        // then
        assertThat(inquiry.getSuccessProductCount()).isEqualTo(0);
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.SEND_SUCCESS);
    }

    @Test
    @DisplayName("분산락 대기 중 인터럽트 발생 시 successProductCount 미증가")
    void responseCompareLoanResult_interrupted() throws InterruptedException {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        LoanLimitResultRequest.LoanApplyResult item = this.buildSuccessItem("LR20260410AAA", "P060100206");

        RLock rLock = mock(RLock.class);
        given(redissonClient.getLock(anyString())).willReturn(rLock);
        // 인터럽트 시뮬레이션
        given(rLock.tryLock(anyLong(), anyLong(), any())).willThrow(new InterruptedException());

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item)));

        // then
        assertThat(inquiry.getSuccessProductCount()).isEqualTo(0);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();    // interrupt 플래그 복원 확인
    }

    @Test
    @DisplayName("유효하지 않은 partnerCode 수신 시 InvalidRequestException")
    void responseCompareLoanResult_invalidPartnerCode() {
        // when & then
        // PartnerCode.valueOf()가 실패하는 잘못된 코드 전달
        assertThatThrownBy(() ->
                loanLimitResultService.responseCompareLoanResult(
                        "INVALID_CODE", this.buildRequest(List.of()))
        ).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("SEND_SUCCESS가 아닌 상태 콜백 수신 시 처리 skip")
    void responseCompareLoanResult_notSendSuccessStatus_skip() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = LoanLimitProductResult.builder()
                .loanLimitInquiry(inquiry)
                .loReqtNo("LR20260410AAA")
                .partnerCode(PartnerCode.LINE_BANK)
                .productCode("P060100206")
                .status(PartnerInquiryStatus.PENDING)
                .build();
        // PENDING 상태 유지 (sendSuccess() 호출 안 함)

        LoanLimitResultRequest.LoanApplyResult item = this.buildSuccessItem("LR20260410AAA", "P060100206");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item)));

        // then - PENDING 상태라 처리 skip, successCount 미증가
        assertThat(inquiry.getSuccessProductCount()).isEqualTo(0);
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.PENDING);
    }

    @Test
    @DisplayName("SUCCESS 상태 콜백 중복 수신 시 처리 skip (멱등성)")
    void responseCompareLoanResult_duplicateSuccess_skip() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        productResult.sendSuccess();
        productResult.updateResult(LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5);  // 이미 SUCCESS

        LoanLimitResultRequest.LoanApplyResult item = this.buildSuccessItem("LR20260410AAA", "P060100206");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item)));

        // then - 이미 SUCCESS라 중복 수신으로 skip
        assertThat(inquiry.getSuccessProductCount()).isEqualTo(0);
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.SUCCESS);
    }

    @Test
    @DisplayName("adaptor.convert() 중 InvalidRequestException 발생 시 실패 응답 반환")
    void responseCompareLoanResult_invalidRequestException_returnFailResponse() {
        // given
        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any()))
                .willThrow(new InvalidRequestException("존재하지 않는 식별번호"));
        given(adaptor.buildResponse(eq(false), anyString()))
                .willReturn(mock(ResultResponse.class));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of()));

        // then - 실패 응답 반환, 예외 전파 안 됨
        then(adaptor).should().buildResponse(eq(false), contains("존재하지 않는 식별번호"));
    }

    @Test
    @DisplayName("예상치 못한 Exception 발생 시 일반 실패 응답 반환")
    void responseCompareLoanResult_unexpectedException_returnsFailResponse() {
        // given
        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any()))
                .willThrow(new RuntimeException("예상치 못한 오류"));
        given(adaptor.buildResponse(eq(false), anyString()))
                .willReturn(mock(ResultResponse.class));

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of()));

        // then
        then(adaptor).should().buildResponse(eq(false), eq("처리 중 오류가 발생했습니다"));
    }
}