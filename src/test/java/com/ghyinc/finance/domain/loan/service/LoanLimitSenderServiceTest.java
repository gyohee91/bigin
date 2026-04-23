package com.ghyinc.finance.domain.loan.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.ghyinc.finance.global.event.impl.KafkaLoanLimitEventPublisher;
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
    private ProductRepository productRepository;

    @Mock
    private LoReqtNoGenerator generator;

    @Mock
    private LoanLimitAdaptorFactory adaptorFactory;

    @Mock
    private KafkaLoanLimitEventPublisher loanLimitEventPublisher;

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
        ReflectionTestUtils.setField(loanLimitSenderService, "loanLimitExecutor", executor);
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

    private Product buildProduct(String productCode) {
        Product product = mock(Product.class);
        given(product.getProductCode()).willReturn(productCode);
        return product;
    }

    @Test
    @DisplayName("전송 성공 - LoanLimitResult SUCCESS, Inquiry SUCCESS")
    void inquiry_sendSuccess() throws JsonProcessingException {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        Product product = this.buildProduct("P060100206");
        given(productRepository.findActiveByPartnerCodeAndLoanType(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(product));
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
        Product product = this.buildProduct("P060100206");
        given(productRepository.findActiveByPartnerCodeAndLoanType(any(), any()))
                .willReturn(List.of(product));

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
    @DisplayName("상품별 신청번호 채번 후 ProductResult에 선저장 - 총 상품 수만큼 INSERT")
    void inquiry_productResultPreSaved_withLoReqtNo() {
        // given
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        Product product1 = this.buildProduct("P060100206");
        Product product2 = this.buildProduct("P060100205");
        given(productRepository.findActiveByPartnerCodeAndLoanType(eq(PartnerCode.LINE_BANK), any()))
                .willReturn(List.of(product1, product2));
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