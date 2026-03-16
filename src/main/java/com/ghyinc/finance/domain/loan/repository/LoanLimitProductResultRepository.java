package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LoanLimitProductResultRepository extends JpaRepository<LoanLimitProductResult, Long> {
    Optional<LoanLimitProductResult> findByLoReqtNo(@Param("loReqtNo") String loReqtNo);

    //@Query("SELECT r.PartnerCode FROM LoanLimitProductResult r WHERE r.loReqtNo = :loReqtNo")
    //Optional<PartnerCode> findPartnerCodeByLoReqtNo(@Param("loReqtNo") String loReqtNo);
}
