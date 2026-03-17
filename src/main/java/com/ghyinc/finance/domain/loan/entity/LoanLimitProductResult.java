package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.LoanLimitResultCode;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoanLimitProductResult extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private LoanLimitInquiry loanLimitInquiry;

    @Comment("신청번호")
    private String loReqtNo;

    @Enumerated(EnumType.STRING)
    @Comment("금융사 코드")
    private PartnerCode partnerCode;

    @Comment("상품 코드")
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Comment("한도조회 결과 코드")
    private LoanLimitResultCode resultCode;

    @Comment("한도금액")
    private Long amount;

    @Comment("금리")
    private double interestRate;

    void assignInquiry(LoanLimitInquiry loanLimitInquiry) {
        this.loanLimitInquiry = loanLimitInquiry;
    }
}
