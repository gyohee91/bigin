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

    @Comment("대출 번호")
    private String loReqtNo;

    @Enumerated(EnumType.STRING)
    @Comment("대출 유형")
    private LoanType loanType;

    private List<LoanLimitResult> results = new ArrayList<>();

    public void addResult(LoanLimitResult result) {
        this.results.add(result);
        result.assignInquiry(this);
    }
}
