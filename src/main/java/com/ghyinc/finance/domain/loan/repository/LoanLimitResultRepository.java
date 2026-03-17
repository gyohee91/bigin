package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoanLimitResultRepository extends JpaRepository<LoanLimitResult, Long> {
    @Query("" +
            "SELECT t.loanLimitInquiry " +
            "FROM LoanLimitResult t " +
            "WHERE t.loReqtNo = :loReqtNo AND t.partnerCode = :partnerCode AND t.status = 'SUCCESS'")
    Optional<LoanLimitInquiry> findInquiryByPartnerCode(
            @Param("loReqtNo") String loReqtNo,
            @Param("partnerCode") PartnerCode partnerCode
    );
}
