package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.adaptor.impl.LoanLimitAdaptor;
import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.factory.LoanLimitAdaptorFactory;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import com.ghyinc.finance.global.exception.ExternalApiFailException;
import com.ghyinc.finance.global.outbox.entity.OutboxEvent;
import com.ghyinc.finance.global.outbox.entity.OutboxStatus;
import com.ghyinc.finance.global.outbox.event.OutboxCreatedEvent;
import com.ghyinc.finance.global.outbox.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class LoanLimitSenderServiceTest {
    @InjectMocks
    private LoanLimitSenderService loanLimitSenderService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private ProductService productService;

    @Mock
    private LoReqtNoGenerator generator;

    @Mock
    private LoanLimitAdaptorFactory adaptorFactory;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.initialize();
        ReflectionTestUtils.setField(loanLimitSenderService, "partnerApiExecutor", executor);
    }

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

    private ProductCache buildProductCache(String productCode, PartnerCode partnerCode) {
        return ProductCache.builder()
                .id(1L)
                .productCode(productCode)
                .productName("신용상품")
                .loanType(LoanType.PERSONAL_CREDIT)
                .partnerCode(partnerCode)
                .active(true)
                .build();
    }

    @Test
    @DisplayName("전송 성공 - LoanLimitResult SUCCESS, Inquiry SUCCESS")
    void inquiry_sendSuccess() throws JsonProcessingException {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        ProductCache productCache = this.buildProductCache("P060100206", PartnerCode.LINE_BANK);
        given(productService.getActiveProducts(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(productCache));
        given(generator.generate("LR")).willReturn("LR20260410AAA");

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

        given(objectMapper.writeValueAsString(any()))
                .willReturn("{\"inquiryNo\":\"LL20260410A3F2C891\"}");

        OutboxEvent savedOutboxEvent = OutboxEvent.builder()
                .aggregateType("LoanLimitInquiry")
                .aggregateId("LL20260410A3F2C891")
                .eventType("LOAN_LIMIT_COMPLETED")
                .status(OutboxStatus.PENDING)
                .build();
        ReflectionTestUtils.setField(savedOutboxEvent, "id", 1L);

        given(outboxEventRepository.save(any(OutboxEvent.class)))
                .willReturn(savedOutboxEvent);

        // when
        loanLimitSenderService.inquiry(1L, List.of(PartnerCode.LINE_BANK), adaptorRequest);

        // then
        //then(loanLimitEventPublisher).should().publishCompletedEvent(any());

        // Outbox INSERT 검증
        ArgumentCaptor<OutboxEvent> outboxCaptor =
                ArgumentCaptor.forClass(OutboxEvent.class);
        then(outboxEventRepository).should().save(outboxCaptor.capture());

        OutboxEvent capturedOutbox = outboxCaptor.getValue();
        assertThat(capturedOutbox.getAggregateType()).isEqualTo("LoanLimitInquiry");
        assertThat(capturedOutbox.getEventType()).isEqualTo("LOAN_LIMIT_COMPLETED");
        assertThat(capturedOutbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

        // Spring 이벤트 발행 검증
        then(applicationEventPublisher).should().publishEvent(any(OutboxCreatedEvent.class));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.SUCCESS);
        assertThat(inquiry.getResults()).hasSize(1);
        assertThat(inquiry.getResults().get(0).getStatus())
                .isEqualTo(InquiryStatus.SUCCESS);

    }

    @Test
    @DisplayName("전송 실패 - LoanLimitResult FAILED, Inquiry FAILED")
    void inquiry_sendFailed_inquiryFailed() {
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        ProductCache productCache = this.buildProductCache("P060100206", PartnerCode.LINE_BANK);
        given(productService.getActiveProducts(any(), any()))
                .willReturn(List.of(productCache));

        LoanLimitAdaptor adaptor = mock(LoanLimitAdaptor.class);
        given(adaptorFactory.getAdaptor(any())).willReturn(adaptor);
        given(adaptor.inquireLimit(any(), any()))
                .willThrow(new ExternalApiFailException("한도조회_ERROR", PartnerCode.LINE_BANK + " 4xx 오류"));
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
        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(inquiry.getResults().get(0).getStatus())
                .isEqualTo(InquiryStatus.FAILED);
    }

    @Test
    @DisplayName("일부 금융사 전송 성공 - Inquiry PARTIAL_SUCCESS")
    void inquiry_partialSecondSuccess_inquiryPartialSuccess() {
        // given
        LoanLimitInquiry loanLimitInquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(loanLimitInquiry));
        ProductCache kakaoProductCache = this.buildProductCache("TA", PartnerCode.KAKAO_BANK);
        ProductCache tossProductCache = this.buildProductCache("FNQ005", PartnerCode.TOSS_BANK);

        given(productService.getActiveProducts(eq(PartnerCode.KAKAO_BANK), any()))
                .willReturn(List.of(kakaoProductCache));
        given(productService.getActiveProducts(eq(PartnerCode.TOSS_BANK), any()))
                .willReturn(List.of(tossProductCache));
        given(generator.generate("LR")).willReturn("LR20260410AAA", "LR20260410BBB");

        LoanLimitAdaptor kakaoAdaptor = mock(LoanLimitAdaptor.class);
        LoanLimitAdaptor tossAdaptor = mock(LoanLimitAdaptor.class);
        given(adaptorFactory.getAdaptor(PartnerCode.KAKAO_BANK)).willReturn(kakaoAdaptor);
        given(adaptorFactory.getAdaptor(PartnerCode.TOSS_BANK)).willReturn(tossAdaptor);
        given(kakaoAdaptor.inquireLimit(eq(PartnerCode.KAKAO_BANK), any()))
                .willReturn(LoanLimitAdaptorResponse.success(PartnerCode.KAKAO_BANK, 100L));
        given(tossAdaptor.inquireLimit(eq(PartnerCode.TOSS_BANK), any()))
                .willThrow(new ExternalApiFailException("한도조회_ERROR", "TOSS_BANK 5xx 오류"));

        LoanLimitAdaptorRequest adaptorRequest = LoanLimitAdaptorRequest.builder()
                .name("윤교희")
                .rrno("9102131234567")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();

        // when
        loanLimitSenderService.inquiry(1L, List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK), adaptorRequest);

        // then
        assertThat(loanLimitInquiry.getStatus()).isEqualTo(InquiryStatus.PARTIAL_SUCCESS);
        assertThat(loanLimitInquiry.getResults()).hasSize(2);
    }

    @Test
    @DisplayName("상품별 신청번호 채번 후 ProductResult에 선저장 - 총 상품 수만큼 INSERT")
    void inquiry_productResultPreSaved_withLoReqtNo() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        ProductCache productCache1 = this.buildProductCache("P060100206", PartnerCode.LINE_BANK);
        ProductCache productCache2 = this.buildProductCache("P060100205", PartnerCode.LINE_BANK);
        given(productService.getActiveProducts(eq(PartnerCode.LINE_BANK), any()))
                .willReturn(List.of(productCache1, productCache2));
        given(generator.generate("LR")).willReturn("LR_AAA", "LR_BBB");

        LoanLimitAdaptor adaptor = mock(LoanLimitAdaptor.class);
        given(adaptorFactory.getAdaptor(any())).willReturn(adaptor);
        given(adaptor.inquireLimit(any(), any()))
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
        assertThat(inquiry.getProductResults()).hasSize(2);
        assertThat(inquiry.getProductResults().get(0).getLoReqtNo()).isEqualTo("LR_AAA");
        assertThat(inquiry.getProductResults().get(1).getLoReqtNo()).isEqualTo("LR_BBB");
        assertThat(inquiry.getTotalProductCount()).isEqualTo(2);
    }
}