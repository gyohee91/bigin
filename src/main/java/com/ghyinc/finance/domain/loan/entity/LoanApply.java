package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.ApplyStatus;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoanApply {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Comment("고객번호")
    private String userId;

    @Comment("신청번호")
    private String loReqtNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inquiry_no", nullable = false)
    private LoanLimitInquiry loanLimitInquiry;

    @Enumerated(EnumType.STRING)
    @Comment("금융사 코드")
    private PartnerCode partnerCode;

    @Comment("상품 코드")
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Comment("신청상태")
    @Builder.Default
    private ApplyStatus status = ApplyStatus.PENDING;
}
