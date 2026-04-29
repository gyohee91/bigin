package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto;
import com.ghyinc.finance.domain.loan.entity.LoanLimitInquiry;
import com.ghyinc.finance.domain.loan.entity.LoanLimitProductResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanLimitProductResultRepository extends JpaRepository<LoanLimitProductResult, Long> {
    Optional<LoanLimitProductResult> findByLoReqtNoAndProductCode(
            @Param("loReqtNo") String loReqtNo,
            @Param("productCode") String productCode
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t.loanLimitInquiry FROM LoanLimitProductResult t WHERE t.loReqtNo = :loReqtNo AND t.productCode = :productCode")
    Optional<LoanLimitInquiry> findInquiryByLoReqtNoAndProduceCodeWithLock(
            @Param("loReqtNo") String loReqtNo,
            @Param("productCode") String productCode
    );

    @Query("SELECT new com.ghyinc.finance.domain.loan.dto.LoanLimitProductResultDto(" +
            "   t.loReqtNo, " +
            "   t.partnerCode, " +
            "   t.productCode, " +
            "   t.resultCode, " +
            "   t.amount, " +
            "   t.interestRate " +
            ") " +
            "FROM LoanLimitProductResult t " +
            "WHERE t.loanLimitInquiry.id = :inquiryId")
    List<LoanLimitProductResultDto> findProductResultsByInquiryId(@Param("inquiryId") Long inquiryId);
}
