package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class PartnerLoanType extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id")
    private Partner partner;

    @Enumerated(EnumType.STRING)
    @Comment("대출 유형")
    private LoanType loanType;

    @Column(nullable = false)
    @Comment("활성 여부")
    private Boolean active;
}
