package com.ghyinc.finance.domain.loan.entity;

import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
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

    @Column(nullable = false, unique = true)
    @Comment("대출 번호")
    private String loReqtNo;

    @Enumerated(EnumType.STRING)
    @Comment("대출 유형")
    private LoanType loanType;

    @Enumerated(EnumType.STRING)
    @Comment("응답 결과")
    @Builder.Default
    private InquiryStatus status = InquiryStatus.PENDING;

    @OneToMany(mappedBy = "loanLimitInquiry", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<LoanLimitResult> results = new ArrayList<>();

    public void updateInquiryStatus(InquiryStatus status) {
        this.status = status;
    }

    public void addResult(LoanLimitResult result) {
        this.results.add(result);
        result.assignInquiry(this);
    }
}
