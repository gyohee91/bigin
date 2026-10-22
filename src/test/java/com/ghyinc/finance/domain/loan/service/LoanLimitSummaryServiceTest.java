package com.ghyinc.finance.domain.loan.service;

import com.ghyinc.finance.domain.loan.dto.*;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.enums.*;
import com.ghyinc.finance.domain.loan.repository.LoanLimitInquiryRepository;
import com.ghyinc.finance.domain.loan.repository.LoanLimitProductResultRepository;
import com.ghyinc.finance.domain.loan.repository.PartnerRepository;
import com.ghyinc.finance.domain.loan.repository.ProductRepository;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class LoanLimitSummaryServiceTest {
    @InjectMocks
    private LoanLimitSummaryService loanLimitSummaryService;

    @Mock
    private LoanLimitInquiryRepository loanLimitInquiryRepository;

    @Mock
    private LoanLimitProductResultRepository loanLimitProductResultRepository;

    @Mock
    private PartnerRepository partnerRepository;

    @Mock
    private ProductRepository productRepository;

    private static final long INQUIRY_ID = 1L;
    private static final String INQUIRY_NO = "LL20260101TEST";

    private LoanLimitInquiry inquiry(LoanLimitResult... partnerResults) {
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder()
                .id(INQUIRY_ID)
                .inquiryNo(INQUIRY_NO)
                .loanType(LoanType.PERSONAL_CREDIT)
                .status(InquiryStatus.IN_PROGRESS)
                .build();

        for(LoanLimitResult r : partnerResults) {
            inquiry.addResult(r);
        }
        return inquiry;
    }

    private LoanLimitResult partnerResult(PartnerCode partnerCode, InquiryStatus status) {
        return LoanLimitResult.builder().partnerCode(partnerCode).status(status).build();
    }

    // 결과 수신 완료 (성공이든 부결이든 resultCode가 있음)
    private LoanLimitProductResultResponse received(PartnerCode partnerCode, String productCode,
                                                    LoanLimitResultCode resultCode, Long amount, double rate) {
        return LoanLimitProductResultResponse.builder()
                .loReqtNo("REQ-" + productCode)
                .partnerCode(partnerCode)
                .productCode(productCode)
                .resultCode(resultCode)
                .amount(amount)
                .interestRate(rate)
                .build();
    }

    // 전송은 성공했고 응답 대기 중
    private LoanLimitProductResultResponse waiting(PartnerCode partnerCode, String productCode) {
        return LoanLimitProductResultResponse.builder()
                .loReqtNo("REQ-" + productCode)
                .partnerCode(partnerCode)
                .productCode(productCode)
                .status(PartnerInquiryStatus.SEND_SUCCESS)
                .build();
    }

    private LoanLimitProductResultResponse sendFailed(PartnerCode partnerCode, String productCode) {
        return LoanLimitProductResultResponse.builder()
                .loReqtNo("REQ-" + productCode)
                .partnerCode(partnerCode)
                .productCode(productCode)
                .status(PartnerInquiryStatus.SEND_FAILED)
                .build();
    }

    private Partner partner(PartnerCode code, String name) {
        return Partner.builder().partnerCode(code).partnerName(name).active(true).build();
    }

    private void givenData(LoanLimitInquiry inquiry,
                           List<LoanLimitProductResultResponse> results,
                           List<Partner> partners,
                           List<ProductNameDto> names
    ) {
        given(loanLimitInquiryRepository.findByInquiryNo(INQUIRY_NO)).willReturn(Optional.of(inquiry));
        given(loanLimitProductResultRepository.findAllByInquiryNo(INQUIRY_ID)).willReturn(results);
        given(partnerRepository.findActiveByPartnerCodes(anyList())).willReturn(partners);
        given(productRepository.findNamesByProductCodes(anyCollection())).willReturn(names);
    }

    // --------------------- 대출 가능 금융사 ---------------------

    @Test
    @DisplayName("금융사별로 묶이고, 금융사 안의 상품은 한도 내림차순이며, 금융사의 최저 금리/최고 한도가 계산된다")
    void groupsProductsByPartner() {
        LoanLimitInquiry inquiry = this.inquiry(
                this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.LINE_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.TOSS_BANK, InquiryStatus.SUCCESS)
        );

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "ta", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5),
                        this.received(PartnerCode.LINE_BANK, "p205", LoanLimitResultCode.SUCCESS, 10_000_000L, 9.5),
                        this.received(PartnerCode.LINE_BANK, "p206", LoanLimitResultCode.SUCCESS, 20_000_000L, 7.5),
                        this.received(PartnerCode.TOSS_BANK, "fnq005", LoanLimitResultCode.SUCCESS, 40_000_000L, 2.5)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크"),
                        this.partner(PartnerCode.LINE_BANK, "라인뱅크"),
                        this.partner(PartnerCode.TOSS_BANK, "토스뱅크")),
                List.of(new ProductNameDto(PartnerCode.KAKAO_BANK, "ta", "갈아타기OK론"),
                        new ProductNameDto(PartnerCode.LINE_BANK, "p205", "드림론"),
                        new ProductNameDto(PartnerCode.LINE_BANK, "p206", "사잇돌"),
                        new ProductNameDto(PartnerCode.TOSS_BANK, "fnq005", "kiwi비상금"))
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.inquiryNo()).isEqualTo(INQUIRY_NO);
        assertThat(response.loanType()).isEqualTo(LoanType.PERSONAL_CREDIT);
        assertThat(response.status()).isEqualTo(InquiryStatus.IN_PROGRESS);
        assertThat(response.unavailablePartners()).isEmpty();
        assertThat(response.availablePartners()).extracting(AvailablePartnerSummary::partnerCode)
                .containsExactly(PartnerCode.TOSS_BANK, PartnerCode.KAKAO_BANK, PartnerCode.LINE_BANK);

        // 같은 금융사의 상품은 한 항목에 묶인다
        AvailablePartnerSummary line = response.availablePartners().get(2);
        assertThat(line.partnerName()).isEqualTo("라인뱅크");
        assertThat(line.lowestInterestRate()).isEqualTo(7.5);
        assertThat(line.highestAmount()).isEqualTo(20_000_000L);
        assertThat(line.products()).extracting(AvailableProductSummary::productName)
                .containsExactly("사잇돌", "드림론");
    }

    @Test
    @DisplayName("금융사는 최고 한도 내림차순이고, 한도가 같으면 최저 금리가 낮은 금융사가 먼저다")
    void sortsPartnersByHighestAmountThenLowestRate() {
        LoanLimitInquiry inquiry = this.inquiry(
                this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.TOSS_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.LINE_BANK, InquiryStatus.SUCCESS)
        );

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5),
                        this.received(PartnerCode.TOSS_BANK, "t1", LoanLimitResultCode.SUCCESS, 30_000_000L, 3.9),
                        this.received(PartnerCode.LINE_BANK, "l1", LoanLimitResultCode.SUCCESS, 50_000_000L, 7.5)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크"),
                        this.partner(PartnerCode.TOSS_BANK, "토스뱅크"),
                        this.partner(PartnerCode.LINE_BANK, "라인뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners()).extracting(AvailablePartnerSummary::partnerCode)
                .containsExactly(PartnerCode.LINE_BANK, PartnerCode.TOSS_BANK, PartnerCode.KAKAO_BANK);
    }

    @Test
    @DisplayName("금융사 안에서 한도가 같으면 금리가 낮은 상품이 먼저다")
    void sortsProductsByAmountThenInterestRate() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.LINE_BANK, InquiryStatus.SUCCESS));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.LINE_BANK, "a", LoanLimitResultCode.SUCCESS, 10_000_000L, 9.5),
                        this.received(PartnerCode.LINE_BANK, "b", LoanLimitResultCode.SUCCESS, 10_000_000L, 7.5)),
                List.of(this.partner(PartnerCode.LINE_BANK, "라인뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners().get(0).products())
                .extracting(AvailableProductSummary::productCode)
                .containsExactly("b", "a");
    }

    @Test
    @DisplayName("상품명을 못 찾으면 상품코드를 상품명으로 쓴다")
    void fallsBackToProductCodeWhenNameMissing() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of());

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners().get(0).products())
                .extracting(AvailableProductSummary::productName)
                .containsExactly("k1");
    }

    @Test
    @DisplayName("DB의 활성화 금융사명이 있으면 그 이름을, 없으면 enum의 이름을 쓴다")
    void usesPartnerNameFromDbOrEnumFallback() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.TOSS_BANK, InquiryStatus.FAILED));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")), // 토스는 비활성화라 조회 안됨
                List.of());

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners().get(0).partnerName()).isEqualTo("카카오뱅크");
        assertThat(response.unavailablePartners()).extracting(UnavailablePartnerSummary::partnerName)
                .containsExactly(PartnerCode.TOSS_BANK.getPartnerName());
    }

    // ---------------- 가능 / 불가 / 대기 분류 ----------------

    @Test
    @DisplayName("한 금융사에서 성공 상품과 부결 상품이 섞이면 대출 가능으로만 분류된다")
    void mixedSuccessAndDeniedIsAvailableOnly() {
        LoanLimitInquiry inquiry =this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS));

        this.givenData(inquiry,
                List.of(
                        this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5),
                        this.received(PartnerCode.KAKAO_BANK, "k2", LoanLimitResultCode.LIMIT_DENIED, null, 0)
                ),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners()).hasSize(1);
        assertThat(response.availablePartners().get(0).products())
                .extracting(AvailableProductSummary::productCode)
                .containsExactly("k1");   // 부결 상품(k2)은 목록에서 제외
        assertThat(response.unavailablePartners()).isEmpty();
    }

    @Test
    @DisplayName("모든 상품이 부결이면 대출 불가 금융사로 분류된다")
    void allDeniedIsUnavailable() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.LIMIT_DENIED, null, 0),
                        this.received(PartnerCode.KAKAO_BANK, "k2", LoanLimitResultCode.LIMIT_DENIED, null, 0)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners()).isEmpty();
        assertThat(response.unavailablePartners())
                .extracting(UnavailablePartnerSummary::partnerCode)
                .containsExactly(PartnerCode.KAKAO_BANK);
    }

    @Test
    @DisplayName("[회귀] 일부 상품은 부결이어도 다른 상품이 응답 대기 중이면 대출 불가로 분류하지 않는다")
    void deniedPlusWaitingIsNotUnavailable() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.PENDING));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.LIMIT_DENIED, null, 0),
                        this.waiting(PartnerCode.KAKAO_BANK, "k2")),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners()).isEmpty();
        assertThat(response.unavailablePartners()).isEmpty();   // 대기 중이라 어느 쪽에도 없음
    }

    @Test
    @DisplayName("모든 상품 전송이 실패하면 대출 불가로 분류된다")
    void allSendFailedIsUnavailable() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.PENDING));

        this.givenData(inquiry,
                List.of(this.sendFailed(PartnerCode.KAKAO_BANK, "k1"), this.sendFailed(PartnerCode.KAKAO_BANK, "k2")),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.unavailablePartners()).hasSize(1);
    }

    @Test
    @DisplayName("금융사 상태가 FAILED이면 상품 행이 없어도 대출 불가로 분류된다")
    void failedPartnerWithoutProductRowsIsUnavailable() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.FAILED));

        this.givenData(inquiry, List.of(), List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")), List.of());

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.unavailablePartners()).hasSize(1);
    }

    @Test
    @DisplayName("상품 행이 아직 없고 진행 중이라면 가능/불가 어느 쪽에도 나오지 않는다")
    void noProductRowsAndNotFailedIsPending() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.PENDING));

        this.givenData(inquiry, List.of(), List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")), List.of());

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners()).isEmpty();
        assertThat(response.unavailablePartners()).isEmpty();
    }

    @Test
    @DisplayName("결과 코드가 SUCCESS여도 한도가 0이면 대출 가능에서 제외되고 불가로 분류된다")
    void zeroAmountSuccessIsUnavailable() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.PENDING));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 0L, 0)),
                List.of(partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availablePartners()).isEmpty();
        assertThat(response.unavailablePartners()).hasSize(1);
    }

    // -------------------- 예외 --------------------

    @Test
    @DisplayName("존재하지 않는 조회번호면 예외가 발생한다")
    void throwWhenInquiryNotFound() {
        given(loanLimitInquiryRepository.findByInquiryNo(INQUIRY_NO))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining(INQUIRY_NO);
    }
}