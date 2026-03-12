package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoanLimitResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_id", nullable = false)
    private LoanLimitInquiry loanLimitInquiry;

    @Column(nullable = false)
    @Comment("신청번호")
    private String loReqtNo;

    @Enumerated(EnumType.STRING)
    @Comment("금융사 코드")
    private PartnerCode partnerCode;

    @Comment("상품코드")
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Comment("응답 결과")
    private InquiryStatus status;

    @Comment("실패 사유")
    private String failReason;

    @Comment("응답 시간")
    private Long resTimeMs;

    void assignInquiry(LoanLimitInquiry loanLimitInquiry) {
        this.loanLimitInquiry = loanLimitInquiry;
    }

    public void success(PartnerCode partnerCode, long resTimeMs) {
        this.partnerCode = partnerCode;
        this.status = InquiryStatus.SUCCESS;
        this.resTimeMs = resTimeMs;
    }

    public void fail(PartnerCode partnerCode, String failReason, long resTimeMs) {
        this.partnerCode = partnerCode;
        this.status = InquiryStatus.FAILED;
        this.failReason = failReason;
        this.resTimeMs = resTimeMs;
    }
}
