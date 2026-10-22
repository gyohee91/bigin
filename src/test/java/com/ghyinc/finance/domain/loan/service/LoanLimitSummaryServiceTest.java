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

    // --------------------- 대출 가능 상품 그룹 ---------------------

    @Test
    @DisplayName("같은 상품의 오퍼는 한 그룹으로 묶이고 한도 내림차순, 최저 금리/최고 한도가 계산된다")
    void groupsOffersByProductName() {
        LoanLimitInquiry inquiry = this.inquiry(
                this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.TOSS_BANK, InquiryStatus.SUCCESS)
        );

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5),
                        this.received(PartnerCode.TOSS_BANK, "t1", LoanLimitResultCode.SUCCESS, 40_000_000L, 3.9)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크"), this.partner(PartnerCode.TOSS_BANK, "토스뱅크")),
                List.of(new ProductNameDto(PartnerCode.KAKAO_BANK, "k1", "신용대출"),
                        new ProductNameDto(PartnerCode.TOSS_BANK, "t1", "신용대출"))
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.inquiryNo()).isEqualTo(INQUIRY_NO);
        assertThat(response.loanType()).isEqualTo(LoanType.PERSONAL_CREDIT);
        assertThat(response.status()).isEqualTo(InquiryStatus.IN_PROGRESS);
        assertThat(response.unavailablePartners()).isEmpty();
        assertThat(response.availableProducts()).hasSize(1);

        ProductGroupSummary group = response.availableProducts().get(0);
        assertThat(group.productName()).isEqualTo("신용대출");
        assertThat(group.lowestInterestRate()).isEqualTo(3.9);
        assertThat(group.highestInterestAmount()).isEqualTo(40_000_000L);
        assertThat(group.offers()).extracting(ProductOfferSummary::partnerCode)
                .containsExactly(PartnerCode.TOSS_BANK, PartnerCode.KAKAO_BANK);
    }

    @Test
    @DisplayName("상품 그룹은 최고 한도 내림차순으로 정렬된다")
    void sortsGroupsByHighestAmountDescending() {
        LoanLimitInquiry inquiry = this.inquiry(
                this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS),
                this.partnerResult(PartnerCode.TOSS_BANK, InquiryStatus.SUCCESS)
        );

        givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of()
        );

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availableProducts()).extracting(ProductGroupSummary::productName)
                .containsExactly("k1");
    }

    @Test
    @DisplayName("상품명을 못 찾으면 상품코드를 그룹명으로 쓴다")
    void fallsBackToProductCodeWhenNameMissing() {
        LoanLimitInquiry inquiry = this.inquiry(this.partnerResult(PartnerCode.KAKAO_BANK, InquiryStatus.SUCCESS));

        this.givenData(inquiry,
                List.of(this.received(PartnerCode.KAKAO_BANK, "k1", LoanLimitResultCode.SUCCESS, 30_000_000L, 4.5)),
                List.of(this.partner(PartnerCode.KAKAO_BANK, "카카오뱅크")),
                List.of());

        LoanLimitSummaryResponse response = loanLimitSummaryService.getCompareLoanSummary(INQUIRY_NO);

        assertThat(response.availableProducts()).extracting(ProductGroupSummary::productName)
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

        assertThat(response.availableProducts().get(0).offers().get(0).partnerName()).isEqualTo("카카오뱅크");
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

        assertThat(response.availableProducts()).hasSize(1);
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

        assertThat(response.availableProducts()).isEmpty();
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

        assertThat(response.availableProducts()).isEmpty();
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

        assertThat(response.availableProducts()).isEmpty();
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

        assertThat(response.availableProducts()).isEmpty();
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