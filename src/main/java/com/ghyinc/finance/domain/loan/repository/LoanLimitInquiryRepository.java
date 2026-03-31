package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanLimitInquiryRepository extends JpaRepository<LoanLimitInquiry, Long> {
    boolean existsByUserIdAndLoanTypeAndStatus(
            @Param("userId") Long userId,
            @Param("loanType") LoanType loanType,
            @Param("status")InquiryStatus status
    );
}
