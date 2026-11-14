package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorRequest;
import com.ghyinc.finance.domain.loan.adaptor.dto.LoanLimitAdaptorResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitInquiryResponse;
import com.ghyinc.finance.domain.loan.dto.LoanLimitRequest;
import com.ghyinc.finance.domain.loan.dto.PreparedFanout;
import com.ghyinc.finance.domain.loan.dto.ProductCache;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import com.ghyinc.finance.domain.loan.readmodel.InquiryReadModelEvent;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.global.common.LoReqtNoGenerator;
import com.ghyinc.finance.global.event.LoanLimitCompletedEvent;
import com.ghyinc.finance.global.event.LoanLimitInquiryCreatedEvent;
import com.ghyinc.finance.global.outbox.service.OutboxEventWriter;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.ghyinc.finance.global.event.PartnerTransmissionBatchAuditEvent;
import com.ghyinc.finance.domain.audit.service.AuditLogWriter;
import com.ghyinc.finance.global.metrics.PartnerSlaMetrics;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class LoanLimitInquiryPersistenceServiceTest {

    private static final String DEDUP_KEY = "1:PERSONAL_CREDIT:20260101";

    private LoanLimitInquiryPersistenceService persistenceService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private ProductService productService;

    @Mock
    private OutboxEventWriter outboxEventWriter;

    @Mock
    private PartnerSlaMetrics partnerSlaMetrics;

    @Mock
    private AuditLogWriter auditLogWriter;

    @Mock
    private LoReqtNoGenerator generator;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @BeforeEach
    void setUp() {
        persistenceService = new LoanLimitInquiryPersistenceService(
                loanLimitInquiryRepository, productService, outboxEventWriter, auditLogWriter,
                partnerSlaMetrics, generator, applicationEventPublisher
        );
    }

    private LoanLimitInquiry buildInquiry() {
        return LoanLimitInquiry.builder()
                .id(1L)
                .inquiryNo("INQ20260101AAA")
                .userId(1L)
                .name("테스트")
                .ci("")
                .jobType(JobType.EMPLOYEE)
                .jobName("테스트회사")
                .loanType(LoanType.PERSONAL_CREDIT)
                .dedupKey(DEDUP_KEY)
                .build();
    }

    private LoanLimitInquiry buildInquiryWithResult(PartnerCode partnerCode, String loReqtNo, String productCode) {
        LoanLimitInquiry inquiry = buildInquiry();
        LoanLimitResult result = LoanLimitResult.builder()
                .partnerCode(partnerCode)
                .build();
        inquiry.addResult(result);
        LoanLimitProductResult productResult = LoanLimitProductResult.builder()
                .loReqtNo(loReqtNo)
                .partnerCode(partnerCode)
                .productCode(productCode)
                .status(PartnerInquiryStatus.PENDING)
                .build();
        inquiry.addProductResult(productResult);
        return inquiry;
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

    private LoanLimitAdaptorRequest buildAdaptorRequest() {
        return LoanLimitAdaptorRequest.builder()
                .name("테스트")
                .rrno("9102131234567")
                .jobType(JobType.EMPLOYEE)
                .jobName("테스트회사")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
    }

    private LoanLimitRequest buildLoanLimitRequest() {
        return LoanLimitRequest.builder()
                .userId(1L)
                .name("테스트")
                .rrno("9102131234567")
                .ci("")
                .jobType(JobType.EMPLOYEE)
                .jobName("테스트회사")
                .loanType(LoanType.PERSONAL_CREDIT)
                .build();
    }

    // ─── createLoanLimitInquiry ───────────────────────────────────────────────

    @Test
    @DisplayName("createLoanLimitInquiry - inquiryNo 채번 후 Inquiry INSERT, 응답에 그대로 반영")
    void createLoanLimitInquiry_savesInquiryWithGeneratedInquiryNo() {
        given(generator.generate("LL")).willReturn("LL20260101abcd1234");
        LoanLimitRequest request = buildLoanLimitRequest();

        LoanLimitInquiryResponse response = persistenceService.createLoanLimitInquiry(
                request, List.of(PartnerCode.LINE_BANK), buildAdaptorRequest(), DEDUP_KEY);

        ArgumentCaptor<LoanLimitInquiry> inquiryCaptor = ArgumentCaptor.forClass(LoanLimitInquiry.class);
        then(loanLimitInquiryRepository).should().save(inquiryCaptor.capture());
        assertThat(inquiryCaptor.getValue().getInquiryNo()).isEqualTo("LL20260101abcd1234");
        assertThat(inquiryCaptor.getValue().getUserId()).isEqualTo(request.userId());
        assertThat(inquiryCaptor.getValue().getName()).isEqualTo(request.name());

        assertThat(response.success()).isTrue();
        assertThat(response.inquiryNo()).isEqualTo("LL20260101abcd1234");
    }

    @Test
    @DisplayName("createLoanLimitInquiry - 저장 후 LoanLimitInquiryCreatedEvent를 발행 (id·금융사 목록·adaptorRequest 포함)")
    void createLoanLimitInquiry_publishesCreatedEventAfterSave() {
        given(generator.generate("LL")).willReturn("LL20260101abcd1234");
        given(loanLimitInquiryRepository.save(any(LoanLimitInquiry.class)))
                .willAnswer(invocation -> {
                    LoanLimitInquiry inquiry = invocation.getArgument(0);
                    ReflectionTestUtils.setField(inquiry, "id", 10L);
                    return inquiry;
                });
        LoanLimitRequest request = buildLoanLimitRequest();
        LoanLimitAdaptorRequest adaptorRequest = buildAdaptorRequest();

        persistenceService.createLoanLimitInquiry(
                request, List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK), adaptorRequest, DEDUP_KEY);

        ArgumentCaptor<LoanLimitInquiryCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(LoanLimitInquiryCreatedEvent.class);
        then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());

        LoanLimitInquiryCreatedEvent event = eventCaptor.getValue();
        assertThat(event.id()).isEqualTo(10L);
        assertThat(event.activePartnerCodes())
                .containsExactly(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK);
        assertThat(event.adaptorRequest()).isEqualTo(adaptorRequest);
    }

    @Test
    @DisplayName("createLoanLimitInquiry - 전달받은 dedupKey 를 저장하고 PENDING 으로 시작 (Redis 락 경로의 하루 1회 방어선)")
    void createLoanLimitInquiry_savesDedupKeyAndStartsPending() {
        given(generator.generate("LL")).willReturn("LL20260101abcd1234");

        persistenceService.createLoanLimitInquiry(
                buildLoanLimitRequest(), List.of(PartnerCode.LINE_BANK), buildAdaptorRequest(), DEDUP_KEY);

        ArgumentCaptor<LoanLimitInquiry> captor = ArgumentCaptor.forClass(LoanLimitInquiry.class);
        then(loanLimitInquiryRepository).should().save(captor.capture());
        assertThat(captor.getValue().getDedupKey()).isEqualTo(DEDUP_KEY);
        assertThat(captor.getValue().getStatus()).isEqualTo(InquiryStatus.PENDING);
    }

    // ─── completeInquiry (Redis 장애 시 DB 선점 경로의 완료 단계) ──────────────

    private LoanLimitInquiry buildAcceptedInquiry() {
        return LoanLimitInquiry.builder()
                .id(10L)
                .inquiryNo("LL_ACCEPTED")
                .userId(1L)
                .loanType(LoanType.PERSONAL_CREDIT)
                .status(InquiryStatus.ACCEPTED)
                .dedupKey(DEDUP_KEY)
                .build();
    }

    @Test
    @DisplayName("completeInquiry - ACCEPTED→PENDING 선점 성공 시 요청 정보를 채우고 CreatedEvent 를 발행, 응답에는 선점 때 채번한 inquiryNo")
    void completeInquiry_success_appliesRequestAndPublishesCreatedEvent() {
        LoanLimitInquiry accepted = buildAcceptedInquiry();
        given(loanLimitInquiryRepository.transit("LL_ACCEPTED", InquiryStatus.ACCEPTED, InquiryStatus.PENDING))
                .willReturn(1);
        given(loanLimitInquiryRepository.findByInquiryNo("LL_ACCEPTED")).willReturn(Optional.of(accepted));
        LoanLimitRequest request = LoanLimitRequest.builder()
                .userId(1L)
                .name("윤교희")
                .rrno("9102131234556")
                .ci("ci-value")
                .jobType(JobType.EMPLOYEE)
                .jobName("오케이")
                .joinDate("202604")
                .carNo("12가1234")
                .loanType(LoanType.PERSONAL_CREDIT)
                .agreePersonalCreditInfo(true)
                .agreePersonalCreditTime(LocalDateTime.of(2026, 1, 1, 9, 30))
                .build();
        LoanLimitAdaptorRequest adaptorRequest = buildAdaptorRequest();

        LoanLimitInquiryResponse response = persistenceService.completeInquiry(
                "LL_ACCEPTED", request, List.of(PartnerCode.KAKAO_BANK), adaptorRequest);

        assertThat(response.inquiryNo()).isEqualTo("LL_ACCEPTED");
        assertThat(response.success()).isTrue();
        // 접수(ACCEPTED) 시점에 비어 있던 요청 정보가 채워졌다
        assertThat(accepted.getName()).isEqualTo("윤교희");
        assertThat(accepted.getCi()).isEqualTo("ci-value");
        assertThat(accepted.getJoinDate()).isEqualTo("202604");
        assertThat(accepted.getCarNo()).isEqualTo("12가1234");
        assertThat(accepted.isAgreePersonalCreditInfo()).isTrue();

        ArgumentCaptor<LoanLimitInquiryCreatedEvent> eventCaptor =
                ArgumentCaptor.forClass(LoanLimitInquiryCreatedEvent.class);
        then(applicationEventPublisher).should().publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().id()).isEqualTo(10L);
        assertThat(eventCaptor.getValue().activePartnerCodes()).containsExactly(PartnerCode.KAKAO_BANK);
        assertThat(eventCaptor.getValue().adaptorRequest()).isEqualTo(adaptorRequest);
    }

    @Test
    @DisplayName("completeInquiry - 상태 선점(transit)이 먼저, 조회가 그 다음. 새 행을 INSERT 하거나 새로 채번하지 않는다")
    void completeInquiry_claimsStatusFirst_andNeverInsertsOrGeneratesNumber() {
        given(loanLimitInquiryRepository.transit("LL_ACCEPTED", InquiryStatus.ACCEPTED, InquiryStatus.PENDING))
                .willReturn(1);
        given(loanLimitInquiryRepository.findByInquiryNo("LL_ACCEPTED"))
                .willReturn(Optional.of(buildAcceptedInquiry()));

        persistenceService.completeInquiry(
                "LL_ACCEPTED", buildLoanLimitRequest(), List.of(PartnerCode.KAKAO_BANK), buildAdaptorRequest());

        InOrder inOrder = inOrder(loanLimitInquiryRepository);
        inOrder.verify(loanLimitInquiryRepository).transit("LL_ACCEPTED", InquiryStatus.ACCEPTED, InquiryStatus.PENDING);
        inOrder.verify(loanLimitInquiryRepository).findByInquiryNo("LL_ACCEPTED");
        then(loanLimitInquiryRepository).should(never()).save(any());
        then(generator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("completeInquiry - 정리 배치가 먼저 FAILED 처리해 선점이 0건이면 예외, 조회·이벤트 발행 없음")
    void completeInquiry_alreadyFailedByCleanup_throwsTimeoutException() {
        given(loanLimitInquiryRepository.transit("LL_ACCEPTED", InquiryStatus.ACCEPTED, InquiryStatus.PENDING))
                .willReturn(0);

        assertThatThrownBy(() -> persistenceService.completeInquiry(
                "LL_ACCEPTED", buildLoanLimitRequest(), List.of(PartnerCode.KAKAO_BANK), buildAdaptorRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessage("요청 처리 시간이 초과되었습니다. 다시 시도해 주세요.");

        then(loanLimitInquiryRepository).should(never()).findByInquiryNo(any());
        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("completeInquiry - 선점은 됐는데 행을 찾을 수 없으면 예외, 이벤트 발행 없음")
    void completeInquiry_inquiryMissingAfterTransit_throws() {
        given(loanLimitInquiryRepository.transit("LL_ACCEPTED", InquiryStatus.ACCEPTED, InquiryStatus.PENDING))
                .willReturn(1);
        given(loanLimitInquiryRepository.findByInquiryNo("LL_ACCEPTED")).willReturn(Optional.empty());

        assertThatThrownBy(() -> persistenceService.completeInquiry(
                "LL_ACCEPTED", buildLoanLimitRequest(), List.of(PartnerCode.KAKAO_BANK), buildAdaptorRequest()))
                .isInstanceOf(NoSuchElementException.class);

        then(applicationEventPublisher).shouldHaveNoInteractions();
    }

    // ─── preSave ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("preSave - Inquiry 상태를 IN_PROGRESS로 전이")
    void preSave_setsInquiryStatusInProgress() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of());

        persistenceService.preSave(1L, List.of(PartnerCode.LINE_BANK), buildAdaptorRequest());

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("preSave - 금융사당 LoanLimitResult 1건 추가")
    void preSave_addsResultPerPartner() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(any(), any())).willReturn(List.of());

        persistenceService.preSave(1L,
                List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK),
                buildAdaptorRequest());

        assertThat(inquiry.getResults()).hasSize(2);
        assertThat(inquiry.getResults())
                .extracting(LoanLimitResult::getPartnerCode)
                .containsExactlyInAnyOrder(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK);
    }

    @Test
    @DisplayName("preSave - 상품당 ProductResult 1건 추가, loReqtNo 채번, totalProductCount 초기화")
    void preSave_addsProductResultWithLoReqtNo_andInitsTotalCount() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(
                        buildProductCache("P001", PartnerCode.LINE_BANK),
                        buildProductCache("P002", PartnerCode.LINE_BANK)
                ));
        given(generator.generate("LR")).willReturn("LR_AAA", "LR_BBB");

        persistenceService.preSave(1L, List.of(PartnerCode.LINE_BANK), buildAdaptorRequest());

        assertThat(inquiry.getProductResults()).hasSize(2);
        assertThat(inquiry.getProductResults().get(0).getLoReqtNo()).isEqualTo("LR_AAA");
        assertThat(inquiry.getProductResults().get(1).getLoReqtNo()).isEqualTo("LR_BBB");
        assertThat(inquiry.getTotalProductCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("preSave - 복수 금융사 전체 상품 수 합산해서 totalProductCount 초기화")
    void preSave_multiplePartners_sumsTotalProductCount() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(eq(PartnerCode.KAKAO_BANK), any()))
                .willReturn(List.of(buildProductCache("TA", PartnerCode.KAKAO_BANK)));
        given(productService.getActiveProducts(eq(PartnerCode.TOSS_BANK), any()))
                .willReturn(List.of(
                        buildProductCache("FNQ001", PartnerCode.TOSS_BANK),
                        buildProductCache("FNQ002", PartnerCode.TOSS_BANK)
                ));
        given(generator.generate("LR")).willReturn("LR_1", "LR_2", "LR_3");

        persistenceService.preSave(1L,
                List.of(PartnerCode.KAKAO_BANK, PartnerCode.TOSS_BANK),
                buildAdaptorRequest());

        assertThat(inquiry.getTotalProductCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("preSave - 반환된 PreparedFanout에 inquiryId와 loReqtNo·productCode 포함")
    void preSave_returnsPreparedFanoutWithCorrectData() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(buildProductCache("P001", PartnerCode.LINE_BANK)));
        given(generator.generate("LR")).willReturn("LR_AAA");

        PreparedFanout result = persistenceService.preSave(1L, List.of(PartnerCode.LINE_BANK), buildAdaptorRequest());

        assertThat(result.inquiryId()).isEqualTo(1L);
        assertThat(result.requestProductMap()).containsKey(PartnerCode.LINE_BANK);
        assertThat(result.requestProductMap().get(PartnerCode.LINE_BANK)).hasSize(1);
        assertThat(result.requestProductMap().get(PartnerCode.LINE_BANK).get(0).loReqtNo()).isEqualTo("LR_AAA");
        assertThat(result.requestProductMap().get(PartnerCode.LINE_BANK).get(0).productCode()).isEqualTo("P001");
    }

    @Test
    @DisplayName("preSave - 존재하지 않는 Inquiry이면 예외 발생")
    void preSave_throwsWhenInquiryNotFound() {
        given(loanLimitInquiryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() ->
                persistenceService.preSave(99L, List.of(PartnerCode.LINE_BANK), buildAdaptorRequest()))
                .isInstanceOf(InvalidRequestException.class);
    }

    // ─── applyResults ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("applyResults - 전체 성공 시 Inquiry 상태 SUCCESS")
    void applyResults_allSuccess_setsInquiryStatusSuccess() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.success(PartnerCode.LINE_BANK, 100L)));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.SUCCESS);
    }

    @Test
    @DisplayName("applyResults - 전체 실패 시 Inquiry 상태 FAILED")
    void applyResults_allFailed_setsInquiryStatusFailed() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.fail(PartnerCode.LINE_BANK, "5xx 오류", 100L)));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.FAILED);
    }

    @Test
    @DisplayName("applyResults - 일부 성공 시 Inquiry 상태 PARTIAL_SUCCESS")
    void applyResults_partialSuccess_setsInquiryStatusPartialSuccess() {
        LoanLimitInquiry inquiry = buildInquiry();
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.KAKAO_BANK).build());
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.TOSS_BANK).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_K001").partnerCode(PartnerCode.KAKAO_BANK).productCode("TA")
                .status(PartnerInquiryStatus.PENDING).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_T001").partnerCode(PartnerCode.TOSS_BANK).productCode("FNQ005")
                .status(PartnerInquiryStatus.PENDING).build());
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L, List.of(
                LoanLimitAdaptorResponse.success(PartnerCode.KAKAO_BANK, 100L),
                LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, "타임아웃", 5000L)
        ));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("applyResults - 성공 응답 시 Result 상태 SUCCESS, ProductResult 상태 SEND_SUCCESS")
    void applyResults_success_updatesResultAndProductResultStatus() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.success(PartnerCode.LINE_BANK, 200L)));

        assertThat(inquiry.getResults().get(0).getStatus()).isEqualTo(InquiryStatus.SUCCESS);
        assertThat(inquiry.getProductResults().get(0).getStatus()).isEqualTo(PartnerInquiryStatus.SEND_SUCCESS);
    }

    @Test
    @DisplayName("applyResults - 실패 응답 시 Result 상태 FAILED, ProductResult 상태 SEND_FAILED")
    void applyResults_failed_updatesResultAndProductResultStatus() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.fail(PartnerCode.LINE_BANK, "CB_OPEN", 0L)));

        assertThat(inquiry.getResults().get(0).getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(inquiry.getProductResults().get(0).getStatus()).isEqualTo(PartnerInquiryStatus.SEND_FAILED);
    }

    @Test
    @DisplayName("applyResults - FAILED가 아닌 경우 LOAN_LIMIT_COMPLETED Outbox 이벤트 발행")
    void applyResults_nonFailed_enqueueLoanLimitCompletedEvent() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.success(PartnerCode.LINE_BANK, 100L)));

        ArgumentCaptor<LoanLimitCompletedEvent> payloadCaptor = ArgumentCaptor.forClass(LoanLimitCompletedEvent.class);
        then(outboxEventWriter).should().enqueue(
                eq("LoanLimitInquiry"),
                eq("INQ20260101AAA"),
                eq("LOAN_LIMIT_COMPLETED"),
                payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo(InquiryStatus.SUCCESS);
    }

    @Test
    @DisplayName("applyResults - PARTIAL_SUCCESS도 LOAN_LIMIT_COMPLETED 이벤트 발행")
    void applyResults_partialSuccess_enqueueLoanLimitCompletedEvent() {
        LoanLimitInquiry inquiry = buildInquiry();
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.KAKAO_BANK).build());
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.TOSS_BANK).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_K").partnerCode(PartnerCode.KAKAO_BANK).productCode("TA")
                .status(PartnerInquiryStatus.PENDING).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_T").partnerCode(PartnerCode.TOSS_BANK).productCode("FNQ")
                .status(PartnerInquiryStatus.PENDING).build());
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L, List.of(
                LoanLimitAdaptorResponse.success(PartnerCode.KAKAO_BANK, 100L),
                LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, "오류", 0L)
        ));

        ArgumentCaptor<LoanLimitCompletedEvent> payloadCaptor = ArgumentCaptor.forClass(LoanLimitCompletedEvent.class);
        then(outboxEventWriter).should().enqueue(
                eq("LoanLimitInquiry"), any(), eq("LOAN_LIMIT_COMPLETED"), payloadCaptor.capture()
        );
        assertThat(payloadCaptor.getValue().getStatus()).isEqualTo(InquiryStatus.PARTIAL_SUCCESS);
    }

    @Test
    @DisplayName("applyResults - 전체 실패 시 LOAN_LIMIT_COMPLETED 이벤트 미발행")
    void applyResults_allFailed_noLoanLimitCompletedEvent() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.fail(PartnerCode.LINE_BANK, "5xx 오류", 100L)));

        then(outboxEventWriter).should(never()).enqueue(
                eq("LoanLimitInquiry"), any(), eq("LOAN_LIMIT_COMPLETED"), any()
        );
    }

    @Test
    @DisplayName("applyResults - 전송 감사는 조회 1건당 audit_log 1행(전 파트너 묶음)을 직접 INSERT")
    void applyResults_writesSingleBatchedTransmissionAuditLog() {
        LoanLimitInquiry inquiry = buildInquiry();
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.KAKAO_BANK).build());
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.TOSS_BANK).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_K").partnerCode(PartnerCode.KAKAO_BANK).productCode("TA")
                .status(PartnerInquiryStatus.PENDING).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_T").partnerCode(PartnerCode.TOSS_BANK).productCode("FNQ")
                .status(PartnerInquiryStatus.PENDING).build());
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L, List.of(
                LoanLimitAdaptorResponse.success(PartnerCode.KAKAO_BANK, 100L),
                LoanLimitAdaptorResponse.success(PartnerCode.TOSS_BANK, 150L)
        ));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        then(auditLogWriter).should(times(1))
                .write(eq("PARTNER_TRANSMISSION"), any(), payload.capture());
        assertThat(((PartnerTransmissionBatchAuditEvent) payload.getValue()).transmissions()).hasSize(2);
        then(partnerSlaMetrics).should(times(2)).recordTransmission(any());
    }

    // ─── markFailed ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("markFailed - 조회 이력이 있으면 Inquiry 상태 FAILED로 전이")
    void markFailed_setsInquiryStatusFailed() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.markFailed(1L);

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.FAILED);
    }

    @Test
    @DisplayName("markFailed - 조회 이력이 없어도 예외 없이 종료")
    void markFailed_noOpWhenInquiryNotFound() {
        given(loanLimitInquiryRepository.findById(99L)).willReturn(Optional.empty());

        persistenceService.markFailed(99L);
        // 예외 없이 정상 종료
    }

    @Test
    @DisplayName("preSave - 전체 상품이 담긴 읽기모델 seed 이벤트를 발행한다")
    void preSave_publishesRealModelSeedEvent() {
        LoanLimitInquiry inquiry = this.buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of(
                        this.buildProductCache("P001", PartnerCode.LINE_BANK),
                        this.buildProductCache("P002", PartnerCode.LINE_BANK)
                ));
        given(generator.generate("LR")).willReturn("LR_AAA", "LR_BBB");

        persistenceService.preSave(1L, List.of(PartnerCode.LINE_BANK), this.buildAdaptorRequest());

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        InquiryReadModelEvent event = captor.getValue();
        assertThat(event.seed()).isTrue();
        assertThat(event.inquiryNo()).isEqualTo("INQ20260101AAA");
        assertThat(event.total()).isEqualTo(2);
        assertThat(event.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(event.items()).extracting("loReqtNo").containsExactlyInAnyOrder("LR_AAA", "LR_BBB");
        assertThat(event.items()).allMatch(i -> i.status() == PartnerInquiryStatus.PENDING);
    }

    @Test
    @DisplayName("applyResults - 결과 상태와 전체 상품 상태를 담은 delta 이벤트를 발행한다")
    void applyResults_publishesReadModelDeltaWithStatusAndItems() {
        LoanLimitInquiry inquiry = this.buildInquiry();
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.KAKAO_BANK).build());
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.TOSS_BANK).build());
        inquiry.addProductResult(LoanLimitProductResult.builder().loReqtNo("LR_A").partnerCode(PartnerCode.KAKAO_BANK).productCode("P1").status(PartnerInquiryStatus.PENDING).build());
        inquiry.addProductResult(LoanLimitProductResult.builder().loReqtNo("LR_B").partnerCode(PartnerCode.TOSS_BANK).productCode("P2").status(PartnerInquiryStatus.PENDING).build());
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L, List.of(
                LoanLimitAdaptorResponse.success(PartnerCode.KAKAO_BANK, 100L),
                LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, "timeout", 5000L))
        );

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        InquiryReadModelEvent event = captor.getValue();
        assertThat(event.seed()).isFalse();
        assertThat(event.total()).isEqualTo(-1);
        assertThat(event.inquiryStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(event.items()).hasSize(2);
        assertThat(event.items()).filteredOn(i -> i.loReqtNo().equals("LR_A"))
                .allMatch(i -> i.status() == PartnerInquiryStatus.SEND_SUCCESS);
        assertThat(event.items()).filteredOn(i -> i.loReqtNo().equals("LR_B"))
                .allMatch(i -> i.status() == PartnerInquiryStatus.SEND_FAILED);

    }

    @Test
    @DisplayName("applyResults - 전체 실패여도 FAILED 상태 delta 이벤트를 발행한다 (폴링 종료 판정용)")
    void applyResults_allFailed_publishesFailedStatusEvent() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.fail(PartnerCode.LINE_BANK, "5xx 오류", 100L)));

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        assertThat(captor.getValue().inquiryStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("markFailed - FAILED 상태 delta 이벤트를 발행한다")
    void markFailed_publishesFailedDeltaEvent() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.markFailed(1L);

        ArgumentCaptor<InquiryReadModelEvent> captor = ArgumentCaptor.forClass(InquiryReadModelEvent.class);
        then(applicationEventPublisher).should().publishEvent(captor.capture());
        InquiryReadModelEvent event = captor.getValue();
        assertThat(event.seed()).isFalse();
        assertThat(event.inquiryNo()).isEqualTo("INQ20260101AAA");
        assertThat(event.inquiryStatus()).isEqualTo("FAILED");
        assertThat(event.items()).isEmpty();
    }

    @Test
    @DisplayName("markFailed - 조회 이력이 없으면 이벤트를 발행하지 않는다")
    void markFailed_doesNotPublish_whenInquiryNotFound() {
        given(loanLimitInquiryRepository.findById(99L)).willReturn(Optional.empty());

        persistenceService.markFailed(99L);

        then(applicationEventPublisher).should(never()).publishEvent(any(Object.class));
    }

    // ─── dedupKey 슬롯 해제 (FAILED 로 전이될 때만) ────────────────────────────

    @Test
    @DisplayName("markFailed - FAILED 전이 시 dedupKey 를 비워 당일 재조회를 허용")
    void markFailed_releasesDedupKey() {
        LoanLimitInquiry inquiry = buildInquiry();
        assertThat(inquiry.getDedupKey()).isEqualTo(DEDUP_KEY);
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.markFailed(1L);

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(inquiry.getDedupKey()).isNull();
    }

    @Test
    @DisplayName("applyResults - 전 제휴사 전송 실패(FAILED)면 dedupKey 를 비워 재조회를 허용")
    void applyResults_allFailed_releasesDedupKey() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.fail(PartnerCode.LINE_BANK, "5xx 오류", 100L)));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.FAILED);
        assertThat(inquiry.getDedupKey()).isNull();
    }

    @Test
    @DisplayName("applyResults - 전체 성공(SUCCESS)이면 dedupKey 유지 (하루 1회 제한)")
    void applyResults_allSuccess_keepsDedupKey() {
        LoanLimitInquiry inquiry = buildInquiryWithResult(PartnerCode.LINE_BANK, "LR_001", "P001");
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L,
                List.of(LoanLimitAdaptorResponse.success(PartnerCode.LINE_BANK, 100L)));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.SUCCESS);
        assertThat(inquiry.getDedupKey()).isEqualTo(DEDUP_KEY);
    }

    @Test
    @DisplayName("applyResults - 일부 성공(PARTIAL_SUCCESS)이어도 dedupKey 유지")
    void applyResults_partialSuccess_keepsDedupKey() {
        LoanLimitInquiry inquiry = buildInquiry();
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.KAKAO_BANK).build());
        inquiry.addResult(LoanLimitResult.builder().partnerCode(PartnerCode.TOSS_BANK).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_K001").partnerCode(PartnerCode.KAKAO_BANK).productCode("TA")
                .status(PartnerInquiryStatus.PENDING).build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR_T001").partnerCode(PartnerCode.TOSS_BANK).productCode("FNQ005")
                .status(PartnerInquiryStatus.PENDING).build());
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));

        persistenceService.applyResults(1L, List.of(
                LoanLimitAdaptorResponse.success(PartnerCode.KAKAO_BANK, 100L),
                LoanLimitAdaptorResponse.fail(PartnerCode.TOSS_BANK, "타임아웃", 5000L)
        ));

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.PARTIAL_SUCCESS);
        assertThat(inquiry.getDedupKey()).isEqualTo(DEDUP_KEY);
    }

    @Test
    @DisplayName("preSave - IN_PROGRESS 전이로는 dedupKey 가 풀리지 않는다")
    void preSave_keepsDedupKey() {
        LoanLimitInquiry inquiry = buildInquiry();
        given(loanLimitInquiryRepository.findById(1L)).willReturn(Optional.of(inquiry));
        given(productService.getActiveProducts(PartnerCode.LINE_BANK, LoanType.PERSONAL_CREDIT))
                .willReturn(List.of());

        persistenceService.preSave(1L, List.of(PartnerCode.LINE_BANK), buildAdaptorRequest());

        assertThat(inquiry.getStatus()).isEqualTo(InquiryStatus.IN_PROGRESS);
        assertThat(inquiry.getDedupKey()).isEqualTo(DEDUP_KEY);
    }
}
