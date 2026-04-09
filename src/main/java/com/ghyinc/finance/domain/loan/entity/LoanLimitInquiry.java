package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.JobType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.global.common.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Comment;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LoanLimitInquiry extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Comment("고객번호")
    private Long userId;

    @Comment("고객명")
    private String name;

    @Enumerated(EnumType.STRING)
    @Comment("직업 구분")
    private JobType jobType;

    @Comment("직장명")
    private String jobName;

    @Enumerated(EnumType.STRING)
    @Comment("대출 유형")
    private LoanType loanType;

    @Comment("차량번호")
    private String carNo;

    @Enumerated(EnumType.STRING)
    @Comment("응답 결과")
    @Builder.Default
    private InquiryStatus status = InquiryStatus.PENDING;

    @Comment("전체 상품 갯수")
    private int totalProductCount;

    @Comment("Success 상품 갯수")
    private int successProductCount;

    @OneToMany(mappedBy = "loanLimitInquiry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LoanLimitResult> results = new ArrayList<>();

    @OneToMany(mappedBy = "loanLimitInquiry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LoanLimitProductResult> productResults = new ArrayList<>();

    public void updateInquiryStatus(InquiryStatus status) {
        this.status = status;
    }

    public void addResult(LoanLimitResult result) {
        this.results.add(result);
        result.assignInquiry(this);
    }

    public void addProductResult(LoanLimitProductResult productResult) {
        this.productResults.add(productResult);
        productResult.assignInquiry(this);
    }

    public void initProductCount(int total) {
        this.totalProductCount = total;
        this.successProductCount = 0;
    }

    public void incrementSuccessCount() {
        this.successProductCount++;
    }
}
