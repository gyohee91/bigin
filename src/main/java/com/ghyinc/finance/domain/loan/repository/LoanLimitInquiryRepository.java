package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.enums.InquiryStatus;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

@Repository
public interface LoanLimitInquiryRepository extends JpaRepository<LoanLimitInquiry, Long> {
    boolean existsByUserIdAndLoanTypeAndStatusIn(
            @Param("userId") Long userId,
            @Param("loanType") LoanType loanType,
            @Param("statuses") Collection<InquiryStatus> statuses
    );

    @Query("SELECT DISTINCT t FROM LoanLimitInquiry t " +
            "   LEFT JOIN FETCH t.productResults " +
            "WHERE t.inquiryNo = :inquiryNo ")
    Optional<LoanLimitInquiry> findByInquiryNoWithProductResults(@Param("inquiryNo") String inquiryNo);

    Optional<LoanLimitInquiry> findByInquiryNo(@Param("inquiryNo") String inquiryNo);

    boolean existsByDedupKey(String dedupKey);

    /**
     * 완료: transit(inquiryNo, ACCEPTED, PENDING)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE LoanLimitInquiry t
           SET t.status = :statusTo
           WHERE t.inquiryNo = :inquiryNo AND t.status = :statusFrom
           """)
    int transit(
            @Param("inquiryNo") String inquiryNo,
            @Param("statusFrom") InquiryStatus statusFrom,
            @Param("statusTo") InquiryStatus statusTo
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
           UPDATE LoanLimitInquiry t
           SET t.status = com.ghyinc.finance.domain.loan.enums.InquiryStatus.FAILED, t.dedupKey = null
           WHERE t.inquiryNo = :inquiryNo AND t.status = com.ghyinc.finance.domain.loan.enums.InquiryStatus.ACCEPTED
           """)
    int failIfAccepted(@Param("inquiryNo") String inquiryNo);
}
