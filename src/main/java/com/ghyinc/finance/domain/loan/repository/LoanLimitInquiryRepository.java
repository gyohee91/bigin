package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoanLimitInquiryRepository extends JpaRepository<LoanLimitInquiry, Long> {
    boolean existsByUserIdAndLoanTypeAndStatus(
            @Param("userId") Long userId,
            @Param("loanType") LoanType loanType,
            @Param("status")InquiryStatus status
    );

    @Query("SELECT DISTINCT t FROM LoanLimitInquiry t " +
            "   LEFT JOIN FETCH t.productResults " +
            "WHERE t.id = :id ")
    Optional<LoanLimitInquiry> findByInquiryNoWithProductResults(@Param("id") long id);
}
