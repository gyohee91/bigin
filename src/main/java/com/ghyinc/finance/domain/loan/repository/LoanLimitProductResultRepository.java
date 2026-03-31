package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM LoanLimitInquiry r WHERE r.id = :id")
    Optional<LoanLimitInquiry> findByIdWithLock(@Param("id") Long id);
}
