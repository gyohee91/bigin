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

    @Enumerated(EnumType.STRING)
    @Comment("금융사 코드")
    private PartnerCode partnerCode;

    @Enumerated(EnumType.STRING)
    @Comment("응답 결과")
    private InquiryStatus inquiryStatus;

    @Comment("실패 사유")
    private String failReason;

    @Comment("응답 시간")
    private Long resTimeMs;

    void assignInquiry(LoanLimitInquiry loanLimitInquiry) {
        this.loanLimitInquiry = loanLimitInquiry;
    }

    public static LoanLimitResult success(PartnerCode partnerCode, long resTimeMs) {
        return LoanLimitResult.builder()
                .partnerCode(partnerCode)
                .inquiryStatus(InquiryStatus.SUCCESS)
                .resTimeMs(resTimeMs)
                .build();
    }

    public static LoanLimitResult fail(PartnerCode partnerCode, String failReason, long resTimeMs) {
        return LoanLimitResult.builder()
                .partnerCode(partnerCode)
                .inquiryStatus(InquiryStatus.FAILED)
                .failReason(failReason)
                .resTimeMs(resTimeMs)
                .build();
    }
}
