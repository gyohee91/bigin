package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoanLimitProductResultRepository extends JpaRepository<LoanLimitProductResult, Long> {
    Optional<LoanLimitProductResult> findByLoReqtNoAndProductCode(
            @Param("loReqtNo") String loReqtNo,
            @Param("productCode") String productCode
    );

    @Query("SELECT r.loanLimitInquiry FROM LoanLimitProductResult r WHERE r.partnerCode = :partnerCode")
    Optional<LoanLimitInquiry> findPartnerCodeByLoReqtNo(@Param("partnerCode") PartnerCode partnerCode);

    Boolean existsByLoReqtNo(@Param("loReqtNo") String loReqtNo);
}
