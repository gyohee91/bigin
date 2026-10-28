package com.ghyinc.finance.domain.loan.readmodel;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.domain.loan.enums.PartnerInquiryStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InquiryReadModelEventTest {

    @Test
    @DisplayName("seed는 전체 상품과 total, status를 담는다")
    void seed_shouldCarryAllProductsTotalAndStatus() {
        LoanLimitInquiry inquiry = LoanLimitInquiry.builder().inquiryNo("LL1").build();
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR1").partnerCode(PartnerCode.TOSS_BANK).productCode("T1")
                .status(PartnerInquiryStatus.PENDING)
                .build());
        inquiry.addProductResult(LoanLimitProductResult.builder()
                .loReqtNo("LR2").partnerCode(PartnerCode.KAKAO_BANK).productCode("K1")
                .status(PartnerInquiryStatus.PENDING)
                .build());
        inquiry.initProductCount(2);
        inquiry.updateInquiryStatus(InquiryStatus.IN_PROGRESS);

        InquiryReadModelEvent event = InquiryReadModelEvent.seed(inquiry);

        assertThat(event.seed()).isTrue();
        assertThat(event.inquiryNo()).isEqualTo("LL1");
        assertThat(event.total()).isEqualTo(2);
        assertThat(event.inquiryStatus()).isEqualTo("IN_PROGRESS");
        assertThat(event.items()).extracting("loReqtNo").containsExactlyInAnyOrder("LR1", "LR2");
    }

    @Test
    @DisplayName("delta의 total은 변경 없음을 뜻하는 -1이다")
    void delta_shouldUseMinusOneForTotal() {
        InquiryReadModelEvent event = InquiryReadModelEvent.delta("LL1", null, List.of());

        assertThat(event.seed()).isFalse();
        assertThat(event.total()).isEqualTo(-1);
        assertThat(event.inquiryStatus()).isNull();
        assertThat(event.items()).isEmpty();
    }
}