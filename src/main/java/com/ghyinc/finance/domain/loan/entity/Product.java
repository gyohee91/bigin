package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.LoanType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Product {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private Partner partner;

    @Enumerated(EnumType.STRING)
    @Comment("대출유형")
    private LoanType loanType;

    @Comment("상품코드")
    private String productCode;

    @Comment("상품명")
    private String productName;
}
