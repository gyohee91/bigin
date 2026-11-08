package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.audit.service.AuditLogWriter;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptor;
import com.ghyinc.finance.domain.loan.adaptor.callback.LoanLimitResultAdaptorFactory;
import com.ghyinc.finance.domain.loan.dto.LoanLimitResultRequest;
import com.ghyinc.finance.domain.loan.dto.ResultResponse;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.*;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelEvent;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.global.lock.RedisLockExecutor;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoanLimitResultServiceTest {

    @InjectMocks
    private LoanLimitResultService loanLimitResultService;

    @Mock
    private LoanLimitResultAdaptorFactory resultAdaptorFactory;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Mock
    private LoanLimitCounterService loanLimitCounterService;

    @Mock
    private RedisLockExecutor lockExecutor;

    @Mock
    private AuditLogWriter auditLogWriter;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

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

    // 락 획득 성공 상황 - action(Runnable)을 그대로 실행
    private void stubLockAcquired() {
        willAnswer(invocation -> {
            Runnable action = invocation.getArgument(3);
            action.run();
            return null;
        }).given(lockExecutor).execute(anyString(), anyLong(), anyLong(), any(Runnable.class), any(Runnable.class));
    }

    // 락 획득 실패 상황 - onLockUnavailable(Runnable)을 실행
    private void stubLockUnavailable() {
        willAnswer(invocation -> {
            Runnable onLockUnavailable = invocation.getArgument(4);
            onLockUnavailable.run();
            return null;
        }).given(lockExecutor).execute(anyString(), anyLong(), anyLong(), any(Runnable.class), any(Runnable.class));
    }

    private LoanLimitInquiry buildInquiryWithNo(String inquiryNo) {
        return LoanLimitInquiry.builder()
                .inquiryNo(inquiryNo)
                .userId(1L)
                .jobType(JobType.EMPLOYEE)
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
    }

    @Test
    @DisplayName("콜백 정상 수신")
    void responseCompareLoanResult() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        LoanLimitResultRequest.LoanApplyResult preScrResultList = this.buildSuccessItem("LR20260410AAA", "P060100206");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(preScrResultList)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR20260410AAA", "P060100206"))
                .willReturn(Optional.of(productResult));
        given(loanLimitCounterService.incrementSuccessCount("LR20260410AAA", "P060100206"))
                .willReturn(1);

        // when
        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(preScrResultList)));

        // then
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.SUCCESS);
        assertThat(productResult.getAmount()).isEqualTo(30_000_000);
        assertThat(productResult.getResultCode()).isEqualTo(LoanLimitResultCode.SUCCESS);
        then(loanLimitCounterService).should().incrementSuccessCount("LR20260410AAA", "P060100206");
        then(auditLogWriter).should().write(eq("PARTNER_CALLBACK"), eq("LR20260410AAA"), any());
    }

    /*
    @Test
    @DisplayName("분산 락 획득 실패 시 successProductCount 미증가")
    void concurrentCallbacks_successProductCountConsistency() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR20260410AAA", "P060100206");
        LoanLimitResultRequest.LoanApplyResult item = this.buildSuccessItem("LR20260410AAA", "P060100206");

        this.stubLockUnavailable();

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
    */

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

        // then - PENDING 상태라 처리 skip, successCount 증가 쿼리 자체를 안 호출
        assertThat(inquiry.getSuccessProductCount()).isEqualTo(0);
        assertThat(productResult.getStatus()).isEqualTo(PartnerInquiryStatus.PENDING);
        then(loanLimitCounterService).should(never()).incrementSuccessCount(anyString(), anyString());
        then(auditLogWriter).should(never()).write(anyString(), anyString(), any());

        then(applicationEventPublisher).should(never()).publishEvent(any(Object.class));
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
        then(loanLimitCounterService).should(never()).incrementSuccessCount(anyString(), anyString());
        then(auditLogWriter).should(never()).write(anyString(), anyString(), any());

        then(applicationEventPublisher).should(never()).publishEvent(any(Object.class));
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

    @Test
    @DisplayName("쿨백 정상 처리 시 read model delta 이벤트를 발행한다")
    void responseCompareLoanResult_publishesReadModelDelta() {
        LoanLimitInquiry inquiry = this.buildInquiryWithNo("INQ1");
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR_A", "P1");
        LoanLimitResultRequest.LoanApplyResult item = this.buildSuccessItem("LR_A", "P1");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR_A", "P1"))
                .willReturn(Optional.of(productResult));

        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item)));

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        InquiryReadModelEvent event = captor.getValue();
        assertThat(event.seed()).isFalse();
        assertThat(event.inquiryNo()).isEqualTo("INQ1");
        assertThat(event.total()).isEqualTo(-1);
        assertThat(event.inquiryStatus()).isNull();
        assertThat(event.items()).hasSize(1);
        assertThat(event.items().get(0).loReqtNo()).isEqualTo("LR_A");
        assertThat(event.items().get(0).status()).isEqualTo(PartnerInquiryStatus.SUCCESS);
        assertThat(event.items().get(0).amount()).isEqualTo(30_000_000L);
    }

    @Test
    @DisplayName("같은 inquiry의 여러 상품 콜백은 이벤트 1건으로 묶어서 발행한다")
    void responseCompareLoanResult_groupsItemsOfSameInquiryIntoOneEvent() {
        LoanLimitInquiry inquiry = this.buildInquiryWithNo("INQ1");
        LoanLimitProductResult productResult1 = this.buildProductResult(inquiry, "LR_A", "P1");
        LoanLimitProductResult productResult2 = this.buildProductResult(inquiry, "LR_B", "P2");
        var item1 = this.buildSuccessItem("LR_A", "P1");
        var item2 = this.buildSuccessItem("LR_B", "P2");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item1, item2)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR_A", "P1")).willReturn(Optional.of(productResult1));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR_B", "P2")).willReturn(Optional.of(productResult2));

        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item1, item2)));

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should(times(1)).publishEvent(captor.capture());
        assertThat(captor.getValue().items()).extracting("loReqtNo").containsExactly("LR_A", "LR_B");
    }

    @Test
    @DisplayName("inquiryNo를 알 수 없으면 이벤트를 발행하지 않는다")
    void responseCompareLoanResult_doesNotPublish_whenInquiryNoMissing() {
        LoanLimitInquiry inquiry = this.buildInquiry();     // inquiryNo 없음
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR_A", "P1");
        var item = this.buildSuccessItem("LR_A", "P1");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR_A", "P1")).willReturn(Optional.of(productResult));

        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item)));

        then(applicationEventPublisher).should(never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("중간 항목에서 예외가 나도 이미 처리된 항목은 이벤트로 발행한다")
    void responseCompareLoanResult_publishesProcessedItems_evenWhenLaterItemFails() {
        LoanLimitInquiry inquiry = this.buildInquiryWithNo("INQ1");
        LoanLimitProductResult productResult = this.buildProductResult(inquiry, "LR_A", "P1");
        var item1 = this.buildSuccessItem("LR_A", "P1");
        var item2 = this.buildSuccessItem("LR_x", "P9");

        LoanLimitResultAdaptor adaptor = mock(LoanLimitResultAdaptor.class);
        given(resultAdaptorFactory.getAdaptor(PartnerCode.LINE_BANK)).willReturn(adaptor);
        given(adaptor.convert(any())).willReturn(this.buildRequestDto(List.of(item1, item2)));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR_A", "P1")).willReturn(Optional.of(productResult));
        given(loanLimitProductResultRepository.findByLoReqtNoAndProductCode("LR_X", "P9")).willReturn(Optional.empty());
        given(adaptor.buildResponse(eq(false), anyString())).willReturn(mock(ResultResponse.class));

        loanLimitResultService.responseCompareLoanResult("LINE_BANK", this.buildRequest(List.of(item1, item2)));

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        assertThat(captor.getValue().items()).extracting("loReqtNo").containsExactly("LR_A");
    }
}
