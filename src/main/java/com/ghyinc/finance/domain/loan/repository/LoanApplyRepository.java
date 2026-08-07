package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.LoanApply;
import com.ghyinc.finance.domain.loan.enums.ApplyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoanApplyRepository extends JpaRepository<LoanApply, Long> {
    boolean existsByLoReqtNoAndStatusNot(
            @Param("loReqtNo") String loReqtNo,
            @Param("status") ApplyStatus status
    );
}
