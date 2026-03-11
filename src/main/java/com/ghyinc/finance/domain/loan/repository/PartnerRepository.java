package com.ghyinc.finance.domain.loan.repository;

import com.ghyinc.finance.domain.loan.entity.Partner;
import com.ghyinc.finance.domain.loan.enums.PartnerCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PartnerRepository extends JpaRepository<Partner, Long> {
    @Query("Select t FROM Partner t WHERE t.partnerCode IN :partnerCodes AND t.active = true")
    List<Partner> findActiveByPartnerCodes(List<PartnerCode> partnerCodes);
}
