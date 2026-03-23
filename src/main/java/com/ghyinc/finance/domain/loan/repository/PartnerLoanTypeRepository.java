package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.PartnerLoanType;
import com.ghyinc.finance.domain.loan.enums.LoanType;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PartnerLoanTypeRepository extends JpaRepository<PartnerLoanType, Long> {
    @Query("SELECT t.partner.partnerCode FROM PartnerLoanType t " +
            "WHERE t.loanType = :loanType " +
            "   AND t.active = true " +
            "   AND t.partner.active = true ")
    List<PartnerCode> findActivePartnerCodeByLoanType(@Param("loanType") LoanType loanType);
}
